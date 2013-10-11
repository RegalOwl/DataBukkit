package regalowl.databukkit;


import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.bukkit.scheduler.BukkitTask;

public class SQLWrite {

	private DataBukkit dab;
	private ConcurrentHashMap<Long, String> buffer = new ConcurrentHashMap<Long, String>();
	private CopyOnWriteArrayList<String> writeStatements = new CopyOnWriteArrayList<String>();
	private BukkitTask writeTask;
	private AtomicBoolean writeActive = new AtomicBoolean();
	private AtomicLong bufferCounter = new AtomicLong();
	private AtomicLong processNext = new AtomicLong();
	private AtomicBoolean logWriteErrors = new AtomicBoolean();;
    private Queue<DatabaseConnection> connections = new LinkedList<DatabaseConnection>();
    private Queue<DatabaseConnection> activeConnections = new LinkedList<DatabaseConnection>();
    private Lock connectionLock = new ReentrantLock();
    private Condition connectionAvailable = connectionLock.newCondition();
	private AtomicBoolean shutDown = new AtomicBoolean();
    private CountDownLatch shutDownLatch = new CountDownLatch(1);
    private long writeInterval;



	public SQLWrite(DataBukkit dabu) {
		logWriteErrors.set(true);
		this.dab = dabu;

		DatabaseConnection dc = null;
		if (dab.useMySQL()) {
			dc = new MySQLConnection(dab);
		} else {
			dc = new SQLiteConnection(dab);
		}
		returnConnection(dc);

		bufferCounter.set(0);
		processNext.set(0);
		writeActive.set(false);
		shutDown.set(false);
		writeInterval = 20L;
		startWrite();
	}
	
	
	
	
	public void returnConnection(DatabaseConnection connection) {
		connectionLock.lock();
		try {
			activeConnections.remove(connection);
			connections.add(connection);
			connectionAvailable.signal();
		} finally {
			connectionLock.unlock();
		}
	}
	private DatabaseConnection getDatabaseConnection() {
		connectionLock.lock();
		try {
			while (connections.isEmpty()) {
				try {
					connectionAvailable.await();
				} catch (InterruptedException e) {
					dab.writeError(e, null);
				}
			}
			DatabaseConnection connect = connections.remove();
			activeConnections.add(connect);
			return connect;
		} finally {
			connectionLock.unlock();
		}
	}
	


	public void executeSQL(List<String> statements) {
		for (String statement : statements) {
			if (statement == null) {continue;}
			buffer.put(bufferCounter.getAndIncrement(), statement);
		}
	}
	public void executeSQL(String statement) {
		if (statement == null) {return;}
		buffer.put(bufferCounter.getAndIncrement(), statement);
	}
	public void convertExecuteSQL(String statement) {
		if (statement == null) {return;}
		buffer.put(bufferCounter.getAndIncrement(), convertSQL(statement));
	}

	private void startWrite() {
		writeTask = dab.getPlugin().getServer().getScheduler().runTaskTimerAsynchronously(dab.getPlugin(), new Runnable() {
			public void run() {
				if (writeActive.get() || buffer.size() == 0) {return;}
				writeActive.set(true);
				DatabaseConnection database = getDatabaseConnection();
				writeStatements.clear();
				while (buffer.size() > 0) {
					writeStatements.add(buffer.get(processNext.get()));
					buffer.remove(processNext.getAndIncrement());
				}
				database.write(getWriteStatements(), logWriteErrors.get());
				writeStatements.clear();
				writeActive.set(false);
				if (shutDown.get()) {
					shutDownLatch.countDown();
				}
			}
		}, writeInterval, writeInterval);
	}

	private CopyOnWriteArrayList<String> getWriteStatements() {
		CopyOnWriteArrayList<String> write = new CopyOnWriteArrayList<String>();
		for(String statement:writeStatements) {
			write.add(statement);
		}
		return write;
	}

	public int getBufferSize() {
		return buffer.size();
	}

	public int getActiveThreads() {
		return 1 - connections.size();
	}

	public ArrayList<String> getBuffer() {
		ArrayList<String> abuffer = new ArrayList<String>();
		for (String item : buffer.values()) {
			abuffer.add(item);
		}
		return abuffer;
	}

	public void shutDown() {
		shutDown.set(true);
		if (writeActive.get()) {
			try {
				//dab.getLogger().info("[DataBukkit["+dab.getPlugin().getName()+"]]Finishing the SQL save task... Please wait.");
				shutDownLatch.await();
			} catch (InterruptedException e) {
				dab.writeError(e);
			}
		}
		if (writeTask != null) {writeTask.cancel();}
		writeActive.set(true);
		if (!connections.isEmpty()){
			connections.remove().closeConnection();
		}
		saveBuffer();
	}
	private void saveBuffer() {
		if (buffer.size() > 0) {
			//dab.getLogger().info("[DataBukkit["+dab.getPlugin().getName()+"]]Saving the SQL buffer... Please wait.");
			DatabaseConnection database = null;
			if (dab.useMySQL()) {
				database = new MySQLConnection(dab);
			} else {
				database = new SQLiteConnection(dab);
			}
			writeStatements.clear();
			for (String s:buffer.values()) {
				writeStatements.add(s);
			}
			database.write(getWriteStatements(), logWriteErrors.get());
			buffer.clear();
			writeStatements.clear();
		}
	}
	
	
	
	
	public void createSqlTable(String name, ArrayList<String> fields) {
		String statement = "CREATE TABLE IF NOT EXISTS " + name + " (";
		for (int i=0; i < fields.size(); i++) {
			String field = convertSQL(fields.get(i));
			if (i < (fields.size() - 1)) {
				statement += field + ", ";
			} else {
				statement += field + ")";
			}
		}
		executeSQL(statement);
	}
	
	public void performInsert(String table, HashMap<String, String> values) {
		String statement = "INSERT INTO " + table + " (";
		for (String field:values.keySet()) {
			statement += field + ", ";
		}
		statement = statement.substring(0, statement.length() - 2);
		statement += ") VALUES (";
		for (String value:values.values()) {
			statement += quoteValue(value) + ", ";
		}
		statement = statement.substring(0, statement.length() - 2);
		statement += ")";		
		executeSQL(statement);
	}
	/**
	 * 
	 * @param table: Name of table
	 * @param values: HashMap<Name of field, String form of value>
	 * 
	 */
	public void performUpdate(String table, HashMap<String, String> values, HashMap<String, String> conditions) {
		String statement = "UPDATE " + table + " SET ";
		Iterator<String> it = values.keySet().iterator();
		while (it.hasNext()) {
			String field = it.next();
			String value = values.get(field);
			statement += field + " = " + quoteValue(value) + ", ";
		}
		statement = statement.substring(0, statement.length() - 2);
		statement += " WHERE ";
		it = conditions.keySet().iterator();
		while (it.hasNext()) {
			String field = it.next();
			String condition = conditions.get(field);
			statement += field + " = " + quoteValue(condition) + " AND ";
		}
		statement = statement.substring(0, statement.length() - 5);
		executeSQL(statement);
	}
	
	public void performDelete(String table, HashMap<String, String> conditions) {
		String statement = "DELETE FROM " + table + " WHERE ";
		Iterator<String> it = conditions.keySet().iterator();
		while (it.hasNext()) {
			String field = it.next();
			String condition = conditions.get(field);
			statement += field + " = " + quoteValue(condition) + " AND ";
		}
		statement = statement.substring(0, statement.length() - 5);
		executeSQL(statement);
	}
	
	public String quoteValue(String value) {
		String valueTest = value.replaceAll("[^()]", "");
		if (valueTest.equalsIgnoreCase("()")) {
			return convertSQL(value);
		} else {
			return "'" + convertSQL(value) + "'";
		}
	}
	
	public String convertSQL(String statement) {
		if (dab.useMySQL()) {
			statement = statement.replace("datetime('NOW', 'localtime')", "NOW()");
			statement = statement.replace("AUTOINCREMENT", "AUTO_INCREMENT");
			statement = statement.replace("autoincrement", "auto_increment");
		} else {
			statement = statement.replace("NOW()", "datetime('NOW', 'localtime')");
			statement = statement.replace("AUTO_INCREMENT", "AUTOINCREMENT");
			statement = statement.replace("auto_increment", "autoincrement");
		}
		return statement;
	}
	
	public void setErrorLogging(boolean state) {
		logWriteErrors.set(state);
	}
	
	public boolean writeActive() {
		return writeActive.get();
	}
	
	/**
	 * This method will call the method of your choice in the class of your choice (the class of the object) after the current write operation is complete.
	 * 
	 * @param object The object that holds the method you want to call.
	 * @param method The name of the method you want to call.
	 */
	public void afterWrite(Object object, String method) {
		new AfterWrite(object, method);
	}
	private class AfterWrite {
		private String m;
		private Object o;
		private BukkitTask afterWriteTask;
		AfterWrite(Object object, String method) {
			this.o = object;
			this.m = method;
			afterWriteTask = dab.getPlugin().getServer().getScheduler().runTaskTimer(dab.getPlugin(), new Runnable() {
				public void run() {
					if (!writeActive() && buffer.size() == 0) {
						try {
							Method meth = o.getClass().getMethod(m);
							meth.invoke(o.getClass().newInstance());
						} catch (Exception e) {
							dab.writeError(e, null);
						} finally {
							afterWriteTask.cancel();
						}
						return;
					}
				}
			}, 1L, 1L);
		}
	}

}
