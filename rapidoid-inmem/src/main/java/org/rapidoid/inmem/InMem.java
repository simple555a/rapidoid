package org.rapidoid.inmem;

/*
 * #%L
 * rapidoid-inmem
 * %%
 * Copyright (C) 2014 Nikolche Mihajlovski
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;

import org.rapidoid.lambda.Callback;
import org.rapidoid.lambda.Operation;
import org.rapidoid.lambda.Predicate;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class Rec {
	final Class<?> type;
	final String json;

	public Rec(Class<?> type, String json) {
		this.type = type;
		this.json = json;
	}
}

/**
 * Simple, persisted in-memory NoSQL DB, based on {@link ConcurrentSkipListMap}.<br>
 * 
 * ACID transactional semantics:<br>
 * - Atomicity with automatic rollback in case of exception,<br>
 * - Consistency - only with constraints enforced programmatically inside transaction,<br>
 * - Isolation is serializable (with global lock),<br>
 * - Durability through on-commit callbacks.<br>
 */
public class InMem {

	private static final String META_UPTIME = "uptime";

	private static final String META_TIMESTAMP = "timestamp";

	private static final String SUFFIX_B = "b";

	private static final String SUFFIX_A = "a";

	private static final byte[] CR_LF = { 13, 10 };

	private static final Object INSERTION = new Object();

	private static final Pattern P_WORD = Pattern.compile("\\w+");

	private final ObjectMapper mapper = new ObjectMapper();

	private final long startedAt = System.currentTimeMillis();

	private final String filename;

	private final AtomicLong ids = new AtomicLong();

	private final AtomicLong lastChangedOn = new AtomicLong();

	private final AtomicLong lastPersistedOn = new AtomicLong();

	private final AtomicBoolean active = new AtomicBoolean(true);

	private final AtomicBoolean aOrB = new AtomicBoolean(true);

	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

	private final Thread persistor;

	private final AtomicBoolean insideTx = new AtomicBoolean(false);

	private final ConcurrentNavigableMap<Long, Rec> txChanges = new ConcurrentSkipListMap<Long, Rec>();

	private final ConcurrentNavigableMap<Long, Object> txInsertions = new ConcurrentSkipListMap<Long, Object>();

	private final ConcurrentLinkedQueue<Callback<Void>> txCallbacks = new ConcurrentLinkedQueue<Callback<Void>>();

	private final AtomicLong txIdCounter = new AtomicLong();

	private ConcurrentNavigableMap<Long, Rec> prevData = new ConcurrentSkipListMap<Long, Rec>();

	private ConcurrentNavigableMap<Long, Rec> data = new ConcurrentSkipListMap<Long, Rec>();

	public InMem() {
		this(null);
	}

	public InMem(String filename) {
		this.filename = filename;

		if (filename != null && !filename.isEmpty()) {

			if (currentFile().exists() && otherFile().exists()) {
				resolveDoubleFileInconsistency();
			}

			load();

			persistor = new Thread(new Runnable() {
				@Override
				public void run() {
					persist();
				}
			});
			persistor.start();
		} else {
			persistor = null;
		}

		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
	}

	private void resolveDoubleFileInconsistency() {
		String file1 = currentFile().getName();
		String file2 = otherFile().getName();

		log("WARN", "The database was left in inconsistent state, both files exist! file1=%s, file2=%s", file1, file2);

		long modif1, modif2;
		try {
			modif1 = (Long) loadMetadata(new FileInputStream(currentFile())).get(META_TIMESTAMP);
			modif2 = (Long) loadMetadata(new FileInputStream(otherFile())).get(META_TIMESTAMP);
		} catch (FileNotFoundException e) {
			throw new RuntimeException(e);
		}

		must(modif1 != modif2,
				"Cannot determine which database file to remove, please remove the incorrect file manually!");

		// delete the most recent file, since it wasn't written completely
		File recent = modif1 > modif2 ? currentFile() : otherFile();

		log("WARN", "The more recent database file is assumed incomplete, so it will be deleted! file=%s", recent);

		recent.delete();
	}

	private void load() {
		try {
			if (currentFile().exists()) {
				loadFrom(new FileInputStream(currentFile()));
				aOrB.set(false);
			} else if (otherFile().exists()) {
				loadFrom(new FileInputStream(otherFile()));
				aOrB.set(true);
			}
		} catch (FileNotFoundException e) {
			throw new IllegalStateException(e);
		}
	}

	public long insert(Object record) {
		sharedLock();
		try {
			long id = ids.incrementAndGet();
			setId(record, id);

			if (insideTx.get()) {
				if (txInsertions.putIfAbsent(id, INSERTION) != null) {
					throw new IllegalStateException("Cannot insert changelog record with existing ID: " + id);
				}
			}

			if (data.putIfAbsent(id, rec(record, id)) != null) {
				throw new IllegalStateException("Cannot insert record with existing ID: " + id);
			}

			lastChangedOn.set(System.currentTimeMillis());
			return id;
		} finally {
			sharedUnlock();
		}
	}

	public void delete(long id) {
		sharedLock();
		try {
			validateId(id);

			Rec removed = data.remove(id);

			if (insideTx.get()) {
				txChanges.putIfAbsent(id, removed);
			}

			lastChangedOn.set(System.currentTimeMillis());

		} finally {
			sharedUnlock();
		}
	}

	public <E> E get(long id) {
		sharedLock();
		try {
			return get_(id, true);
		} finally {
			sharedUnlock();
		}
	}

	public <E> E getIfExists(long id) {
		sharedLock();
		try {
			return get_(id, false);
		} finally {
			sharedUnlock();
		}
	}

	@SuppressWarnings("unchecked")
	private <E> E get_(long id, boolean validateId) {
		if (validateId) {
			validateId(id);
		}
		Rec rec = data.get(id);
		return (E) (rec != null ? setId(obj(rec), id) : null);
	}

	public <E> E get(long id, Class<E> clazz) {
		sharedLock();
		try {
			validateId(id);
			return get_(id, clazz);
		} finally {
			sharedUnlock();
		}
	}

	public void update(long id, Object record) {
		sharedLock();
		try {
			validateId(id);

			setId(record, id);

			Rec removed = data.replace(id, rec(record, id));

			if (insideTx.get()) {
				txChanges.putIfAbsent(id, removed);
			}

			if (removed == null) {
				throw new IllegalStateException("Cannot update non-existing record with ID=" + id);
			}

			lastChangedOn.set(System.currentTimeMillis());
		} finally {
			sharedUnlock();
		}
	}

	public void update(Object record) {
		update(getIdOf(record, true), record);
	}

	public long persist(Object record) {
		long id = getIdOf(record, true);
		if (id <= 0) {
			return insert(record);
		} else {
			update(id, record);
			return id;
		}
	}

	@SuppressWarnings("unchecked")
	public <T> T read(long id, String column) {
		sharedLock();
		try {
			validateId(id);
			Map<String, Object> map = get_(id, Map.class);
			return (T) (map != null ? map.get(column) : null);
		} finally {
			sharedUnlock();
		}
	}

	public <E> List<E> getAll(final Class<E> clazz) {
		return find(new Predicate<E>() {
			@Override
			public boolean eval(E record) throws Exception {
				return clazz.isAssignableFrom(record.getClass());
			}
		});
	}

	public <E> List<E> getAll(final Class<E> clazz, String orderBy) {
		List<E> results = getAll(clazz);

		final int sign = orderBy.startsWith("-") ? -1 : 1;
		final String order = sign == 1 ? orderBy : orderBy.substring(1);

		Comparator<E> comparator = new Comparator<E>() {
			@SuppressWarnings("unchecked")
			@Override
			public int compare(E o1, E o2) {

				E val1 = getProperty(o1, order, true);
				E val2 = getProperty(o2, order, true);

				must(val1 instanceof Comparable && val2 instanceof Comparable, "The property '%s' is not comparable!",
						order);

				return sign * ((Comparable<E>) val1).compareTo(val2);
			}
		};

		Collections.sort(results, comparator);

		return results;
	}

	@SuppressWarnings("unchecked")
	public <E> List<E> getAll(long[] ids) {
		List<E> results = new ArrayList<E>(ids.length);

		sharedLock();
		try {

			for (long id : ids) {
				results.add((E) get_(id, true));
			}

			return results;
		} finally {
			sharedUnlock();
		}
	}

	@SuppressWarnings("unchecked")
	public <E> List<E> getAll(Collection<Long> ids) {
		List<E> results = new ArrayList<E>(ids.size());

		sharedLock();
		try {

			for (long id : ids) {
				results.add((E) get_(id, true));
			}

			return results;
		} finally {
			sharedUnlock();
		}
	}

	public <E> List<E> find(final Predicate<E> match) {
		final List<E> results = new ArrayList<E>();

		each(new Operation<E>() {

			@Override
			public void execute(E record) throws Exception {
				if (match.eval(record)) {
					results.add(record);
				}
			}
		});

		return results;
	}

	public <E> List<E> find(String searchPhrase) {

		String search = searchPhrase.toLowerCase();
		List<E> results = new ArrayList<E>();

		sharedLock();
		try {

			for (Entry<Long, Rec> entry : data.entrySet()) {
				if (entry.getValue().json.toLowerCase().contains(search)) {
					E record = obj(entry.getValue());
					setId(record, entry.getKey());
					results.add(record);
				}
			}
		} finally {
			sharedUnlock();
		}

		return results;
	}

	public <E> List<E> find(final Class<E> clazz, final String query, final Object... args) {

		Predicate<E> match = new Predicate<E>() {
			@Override
			public boolean eval(E record) throws Exception {
				return clazz.isAssignableFrom(record.getClass()) && matches(record, query, args);
			}
		};

		return find(match);
	}

	public boolean matches(Object record, String query, Object... args) {

		if (query == null || query.isEmpty()) {
			return true;
		}

		if (P_WORD.matcher(query).matches() && args.length == 1) {
			Object val = getProperty(record, query, false);
			Object arg = args[0];
			return val == arg || (val != null && val.equals(arg));
		}

		throw new RuntimeException("Query not supported: " + query);
	}

	public <E> void each(final Operation<E> lambda) {
		sharedLock();
		try {

			for (Entry<Long, Rec> entry : data.entrySet()) {
				E record = obj(entry.getValue());
				setId(record, entry.getKey());

				try {
					lambda.execute(record);
				} catch (ClassCastException e) {
					// ignore, cast exceptions are expected
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}
		} finally {
			sharedUnlock();
		}
	}

	/**
	 * Simple, persisted in-memory NoSQL DB, based on {@link ConcurrentSkipListMap}.<br>
	 * 
	 * ACID transactional semantics:<br>
	 * - Atomicity with automatic rollback in case of exception,<br>
	 * - Consistency - only with constraints enforced programmatically inside transaction,<br>
	 * - Isolation is serializable (with global lock),<br>
	 * - Durability through on-commit callbacks (this method is blocking).<br>
	 */
	public void transaction(Runnable transaction, boolean readOnly) {

		final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
		final CountDownLatch latch = new CountDownLatch(1);

		Callback<Void> txCallback = new Callback<Void>() {
			@Override
			public void onDone(Void result, Throwable e) {
				latch.countDown();
				error.set(e);
			}
		};

		transaction(transaction, readOnly, txCallback);

		try {
			latch.await();
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}

		if (error.get() != null) {
			throw new RuntimeException("Transaction failure!", error.get());
		}
	}

	/**
	 * ACID transactional semantics:<br>
	 * - Atomicity with automatic rollback in case of exception,<br>
	 * - Consistency - only with constraints enforced programmatically inside transaction,<br>
	 * - Isolation is serializable (with global lock),<br>
	 * - Durability through on-commit callbacks.<br>
	 */
	public void transaction(Runnable transaction, boolean readOnly, Callback<Void> txCallback) {
		globalLock();

		txIdCounter.set(ids.get());
		txChanges.clear();
		txInsertions.clear();

		insideTx.set(true);

		try {
			transaction.run();

			if (txCallback != null) {
				txCallbacks.add(txCallback);
			}

		} catch (Throwable e) {
			error("Error in transaction, rolling back", e);
			txRollback();
			if (txCallback != null) {
				txCallback.onDone(null, e);
			}

		} finally {
			txChanges.clear();
			txInsertions.clear();
			insideTx.set(false);
			globalUnlock();
		}
	}

	private void txRollback() {
		ids.set(txIdCounter.get());

		for (Entry<Long, Rec> e : txChanges.entrySet()) {
			Long id = e.getKey();
			Rec value = e.getValue();
			must(value != null, "Cannot have null value!");
			data.put(id, value);
		}

		for (Entry<Long, Object> e : txInsertions.entrySet()) {
			// rollback insert operation
			Long id = e.getKey();
			Object value = e.getValue();
			must(value == INSERTION, "Expected insertion mode!");

			Rec inserted = data.remove(id);
			must(inserted != null, "Cannot have null insertion!");
		}
	}

	private <T> T get_(long id, Class<T> clazz) {
		validateId(id);
		Rec rec = data.get(id);
		return rec != null ? setId(parse(rec.json, clazz), id) : null;
	}

	private void sharedLock() {
		lock.readLock().lock();
	}

	private void sharedUnlock() {
		lock.readLock().unlock();
	}

	private void globalLock() {
		lock.writeLock().lock();
	}

	private void globalUnlock() {
		lock.writeLock().unlock();
	}

	private void validateId(long id) {
		if (!data.containsKey(id)) {
			throw new IllegalArgumentException("Cannot find DB record with id=" + id);
		}
	}

	public void saveTo(final OutputStream output) {
		globalLock();

		try {
			PrintWriter out = new PrintWriter(output);

			out.println(stringify(metadata()));

			for (Entry<Long, Rec> entry : data.entrySet()) {
				String json = entry.getValue().json;
				out.println(json);
			}

			out.close();
		} finally {
			globalUnlock();
		}
	}

	@SuppressWarnings("unchecked")
	public Map<String, Object> loadMetadata(InputStream in) {
		globalLock();

		try {
			BufferedReader reader = new BufferedReader(new InputStreamReader(in));

			String line = reader.readLine();
			must(line != null, "Missing meta-data at the first line in the database file!");

			Map<String, Object> meta = parse(line, Map.class);

			reader.close();

			return meta;

		} catch (IOException e) {
			throw new RuntimeException("Cannot load meta-data from database!", e);
		} finally {
			globalUnlock();
		}
	}

	@SuppressWarnings("unchecked")
	public void loadFrom(InputStream in) {
		globalLock();

		try {
			data.clear();

			BufferedReader reader = new BufferedReader(new InputStreamReader(in));

			String line = reader.readLine();

			must(line != null, "Missing meta-data at the first line in the database file!");

			Map<String, Object> meta = parse(line, Map.class);
			log("INFO", "Database meta-data: %s=%s, %s=%s", META_TIMESTAMP, meta.get(META_TIMESTAMP), META_UPTIME,
					meta.get(META_UPTIME));

			while ((line = reader.readLine()) != null) {
				Map<String, Object> map = parse(line, Map.class);
				Long id = new Long(((String) map.get("id")));
				String className = ((String) map.get("_class"));

				Class<?> type;
				try {
					type = Class.forName(className);
				} catch (ClassNotFoundException e) {
					type = null;
				}

				data.put(id, new Rec(type, line));

				if (id > ids.get()) {
					ids.set(id);
				}
			}

			prevData = new ConcurrentSkipListMap<Long, Rec>(data);

			reader.close();

		} catch (IOException e) {
			throw new RuntimeException("Cannot load database!", e);
		} finally {
			globalUnlock();
		}
	}

	private void persistTo(RandomAccessFile file) throws IOException {
		file.write(stringify(metadata()).getBytes());
		file.write(CR_LF);
		for (Entry<Long, Rec> entry : data.entrySet()) {
			String json = entry.getValue().json;
			file.write(json.getBytes());
			file.write(CR_LF);
		}
	}

	private Map<String, Object> metadata() {
		Map<String, Object> meta = new HashMap<String, Object>();

		long now = System.currentTimeMillis();

		meta.put(META_TIMESTAMP, now);
		meta.put(META_UPTIME, now - startedAt);

		return meta;
	}

	@SuppressWarnings("unchecked")
	private void persistData() {
		globalLock();

		final ConcurrentNavigableMap<Long, Rec> copy;
		Callback<Void>[] callbacks;

		try {
			if (data.isEmpty()) {
				must(txCallbacks.isEmpty(), "Inconsistent DB state!");
				return;
			}

			copy = new ConcurrentSkipListMap<Long, Rec>(data);

			callbacks = txCallbacks.toArray(new Callback[txCallbacks.size()]);
			txCallbacks.clear();

		} finally {
			globalUnlock();
		}

		if (lastChangedOn.get() < lastPersistedOn.get()) {
			invokeCallbacks(callbacks, null);
			return;
		}

		lastPersistedOn.set(System.currentTimeMillis());

		try {
			File file = currentFile();

			if (file.exists()) {
				throw new IllegalStateException("Cannot save the database, file already exists: "
						+ file.getAbsolutePath());
			}

			RandomAccessFile raf = new RandomAccessFile(file, "rw");
			persistTo(raf);
			raf.getChannel().force(false);
			raf.close();

			prevData = copy;
			boolean isA = aOrB.get();
			must(aOrB.compareAndSet(isA, !isA), "DB persistence file switching error!");

			File oldFile = currentFile();
			oldFile.delete();

		} catch (IOException e) {

			invokeCallbacks(callbacks, e);

			data = new ConcurrentSkipListMap<Long, Rec>(prevData);

			throw new RuntimeException("Cannot persist database changes!", e);
		}

		invokeCallbacks(callbacks, null);
	}

	private void invokeCallbacks(Callback<Void>[] callbacks, Throwable e) {
		for (Callback<Void> callback : callbacks) {
			try {
				callback.onDone(null, e);
			} catch (Throwable e2) {
				error("Transaction callback error", e2);
			}
		}
	}

	private File currentFile() {
		return new File(filenameWithSuffix(aOrB.get() ? SUFFIX_A : SUFFIX_B));
	}

	private File otherFile() {
		return new File(filenameWithSuffix(!aOrB.get() ? SUFFIX_A : SUFFIX_B));
	}

	private String filenameWithSuffix(String suffixAorB) {
		return filename.replace(".db", "-" + suffixAorB + ".db");
	}

	private void persist() {
		while (!Thread.interrupted()) {
			try {
				persistData();
			} catch (Exception e1) {
				error("Failed to persist data!", e1);
			}

			if (active.get()) {
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
				}
			} else {
				try {
					persistData();
				} catch (Exception e1) {
					error("Failed to persist data!", e1);
				}
				return;
			}
		}
	}

	public void shutdown() {
		active.set(false);

		try {
			persistor.join();
		} catch (InterruptedException e) {
		}

		new File(filename).delete();
	}

	public boolean isActive() {
		return active.get();
	}

	@Override
	public String toString() {
		return super.toString() + "[filename=" + filename + "]";
	}

	public void start() {
		// TODO implement start after shutdown
		if (!active.get()) {
			throw new IllegalStateException("Starting the database after shutdown is not implemented yet!");
		}
	}

	public void halt() {
		if (active.get()) {
			active.set(false);

			persistor.interrupt();
			try {
				persistor.join();
			} catch (InterruptedException e) {
			}
		}
	}

	public void destroy() {
		halt();
		new File(filenameWithSuffix(SUFFIX_A)).delete();
		new File(filenameWithSuffix(SUFFIX_B)).delete();
	}

	private Rec rec(Object record, long id) {
		String _class = record.getClass().getCanonicalName();
		return new Rec(record.getClass(), stringifyWithExtras(record, "_class", _class, "id", id));
	}

	@SuppressWarnings("unchecked")
	private <T> T obj(Rec rec) {
		return (T) parse(rec.json, rec.type);
	}

	@SuppressWarnings("unchecked")
	private static <T> T setId(T record, long id) {
		if (record != null) {

			if (record instanceof Map) {
				((Map<Object, Object>) record).put("id", id);
			}

			try {
				setObjId(record, id);
			} catch (Exception e) {
				// ignore
			}
		}

		return record;
	}

	private static void must(boolean condition, String msg, Object... args) {
		if (!condition) {
			throw new RuntimeException(String.format(msg, args));
		}
	}

	private static void log(String level, String msg, Object... args) {
		System.out.println(level + " | " + String.format(msg, args));
	}

	private static void error(String msg, Throwable e) {
		System.err.println(msg);
		e.printStackTrace();
	}

	public static void setObjId(Object obj, long id) {
		Class<?> c = obj.getClass();

		try {
			try {
				c.getMethod("setId", long.class).invoke(obj, id);
				return;
			} catch (NoSuchMethodException e1) {
				try {
					c.getMethod("id", long.class).invoke(obj, id);
					return;
				} catch (NoSuchMethodException e2) {
					try {
						c.getField("id").set(obj, id);
						return;
					} catch (NoSuchFieldException e3) {
					}
				}
			}
		} catch (Exception e) {
			throw new RuntimeException("Cannot get object id!", e);
		}

		throw new RuntimeException(
				"Cannot find public 'id' field nor 'setId' setter method nor 'id' setter method in class: " + c);
	}

	public static long getIdOf(Object obj, boolean failIfNotFound) {
		Number id = (Number) getProperty(obj, "id", failIfNotFound);
		return id != null ? id.longValue() : -1;
	}

	@SuppressWarnings("unchecked")
	public static <T> T getProperty(Object obj, String property, boolean failIfNotFound) {
		Class<?> c = obj.getClass();

		String capitalized = property.substring(0, 1).toUpperCase() + property.substring(1);
		String getter = "get" + capitalized;

		try {
			try {
				return (T) c.getMethod(getter).invoke(obj);
			} catch (NoSuchMethodException e1) {
				try {
					return (T) c.getMethod(property).invoke(obj);
				} catch (NoSuchMethodException e2) {
					try {
						return (T) c.getField(property).get(obj);
					} catch (NoSuchFieldException e3) {
					}
				}
			}
		} catch (Exception e) {
			throw new RuntimeException("Cannot get property: " + property, e);
		}

		if (failIfNotFound) {
			throw new RuntimeException(String.format(
					"Cannot find public '%s' field nor '%s' getter method nor '%s' getter method in class: %s",
					property, getter, property, c));
		} else {
			return null;
		}
	}

	public int size() {
		globalLock();
		try {
			return data.size();
		} finally {
			globalUnlock();
		}
	}

	public ObjectMapper getMapper() {
		return mapper;
	}

	public String stringify(Object value) {
		try {
			return mapper.writeValueAsString(value);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * @param extras
	 *            extra JSON attributes in format (key1, value1, key2, value2...)
	 */
	private String stringifyWithExtras(Object value, Object... extras) {
		if (extras.length % 2 != 0) {
			throw new IllegalArgumentException(
					"Expected even number of extras (key1, value1, key2, value2...), but found: " + extras.length);
		}

		try {
			JsonNode node = mapper.valueToTree(value);

			if (!(node instanceof ObjectNode)) {
				throw new RuntimeException("Cannot add extra attributes on a non-object value: " + value);
			}

			ObjectNode obj = (ObjectNode) node;

			int extrasN = extras.length / 2;
			for (int i = 0; i < extrasN; i++) {
				Object key = extras[2 * i];
				if (key instanceof String) {
					obj.put((String) key, String.valueOf(extras[2 * i + 1]));
				} else {
					throw new RuntimeException("Expected extra key of type String, but found: " + key);
				}
			}

			return mapper.writeValueAsString(node);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public <T> T parse(String json, Class<T> valueType) {
		try {
			return mapper.readValue(json, valueType);
		} catch (Exception e) {
			error("Cannot parse JSON!", e);
			throw new RuntimeException(e);
		}
	}

}
