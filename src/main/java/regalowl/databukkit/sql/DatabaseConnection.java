package regalowl.databukkit.sql;



import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import regalowl.databukkit.DataBukkit;
import regalowl.databukkit.events.LogEvent;
import regalowl.databukkit.events.LogLevel;
import regalowl.databukkit.events.ShutdownEvent;



public class DatabaseConnection {

	private DataBukkit dab;
	private Connection connection;
    private AtomicBoolean readOnly = new AtomicBoolean();
    private AtomicBoolean lock = new AtomicBoolean();
    
	public DatabaseConnection(DataBukkit dab, boolean readOnly) {
		this.lock.set(false);
		this.dab = dab;
		this.readOnly.set(readOnly);
	}
	
	
	
	public synchronized WriteResult write(List<WriteStatement> statements) {
		WriteStatement currentStatement = null;
		PreparedStatement preparedStatement = null;
		try {
			prepareConnection();
			if (statements.size() == 0 || lock.get()) {return new WriteResult(WriteResultType.EMPTY, statements);}
			for (WriteStatement statement : statements) {
				currentStatement = statement;
				preparedStatement = connection.prepareStatement(currentStatement.getStatement());
				currentStatement.applyParameters(preparedStatement);
				preparedStatement.executeUpdate();
			}
			if (lock.get()) {
				connection.rollback();
				return new WriteResult(WriteResultType.DISABLED, null, null, null, statements);
			} else {
				connection.commit();
				return new WriteResult(WriteResultType.SUCCESS, statements);
			}
		} catch (SQLException e) {
			try {
				connection.rollback();
				statements.remove(currentStatement);
				return new WriteResult(WriteResultType.ERROR, null, currentStatement, e, statements);
			} catch (SQLException e1) {
				dab.writeError(e, "Rollback failed.");
				return new WriteResult(WriteResultType.ERROR, null, null, null, statements);
			}
		} finally {
			try {
				if (preparedStatement != null) {
					preparedStatement.close();
				};
			} catch (SQLException e) {
				dab.writeError(e);
			}
		}
	}

	public synchronized QueryResult read(BasicStatement statement) {
		QueryResult qr = new QueryResult();
		PreparedStatement preparedStatement = null;
		ResultSet resultSet = null;
		try {
			prepareConnection();
			preparedStatement = connection.prepareStatement(statement.getStatement());
			statement.applyParameters(preparedStatement);
			resultSet = preparedStatement.executeQuery();
			ResultSetMetaData rsmd = resultSet.getMetaData();
			int columnCount = rsmd.getColumnCount();
			for (int i = 1; i <= columnCount; i++) {
				qr.addColumnName(rsmd.getColumnLabel(i));
			}
			while (resultSet.next()) {
				for (int i = 1; i <= columnCount; i++) {
					qr.addData(i, resultSet.getString(i));
				}
			}
			return qr;
		} catch (SQLException e) {
			qr.setException(e, statement.getStatement());
			return qr;
		} finally {
			try {
				if (preparedStatement != null) {
					preparedStatement.close();
				}
				if (resultSet != null) {
					resultSet.close();
				}
			} catch (SQLException e) {
				dab.writeError(e);
			}
		}
	}

	
	public void lock() {
		this.lock.set(true);
	}
	
	
	
	public synchronized void prepareConnection() {
		if (!isValid()) {fixConnection();}
	}
	private synchronized boolean isValid() {
		try {
			if (connection == null || connection.isClosed()) {
				return false;
			}
			if (!readOnly.get() && connection.isReadOnly()) {
				return false;
			}
			if (!readOnly.get()) {
				try {
					connection.setAutoCommit(false);
				} catch (SQLException se) {
					return false;
				}
			}
		} catch (SQLException e) {
			return false;
		}
		return true;
	}
	private synchronized void fixConnection() {
		closeConnection();
		openConnection();
		if (isValid()) {return;}
		if (readOnly.get()) {
			dab.getEventPublisher().fireEvent(new LogEvent("[" + dab.getName() + "]Fatal database connection error. " 
		+ "Make sure your database is unlocked and readable in order to use this plugin." + " Disabling " 
					+ dab.getName() + ".", null, LogLevel.SEVERE));
		} else {
			dab.getEventPublisher().fireEvent(new LogEvent("[" + dab.getName() + "]Fatal database connection error. " 
		+ "Make sure your database is unlocked and writeable in order to use this plugin." + " Disabling " + dab.getName() + ".", null, LogLevel.SEVERE));
		}
		dab.getEventPublisher().fireEvent(new ShutdownEvent());
	}
	public synchronized void openConnection() {
		if (dab.getSQLManager().useMySQL()) {
			try {
				String username = dab.getSQLManager().getUsername();
				String password = dab.getSQLManager().getPassword();
				int port = dab.getSQLManager().getPort();
				String host = dab.getSQLManager().getHost();
				String database = dab.getSQLManager().getDatabase();
				connection = DriverManager.getConnection("jdbc:mysql://" + host + ":" + port + "/" + database, username, password);
				connection.setReadOnly(readOnly.get());
			} catch (Exception e) {
				dab.writeError(e, "Database connection error.");
			}
		} else {
			try {
				String sqlitePath = dab.getSQLManager().getSQLitePath();
				Class.forName("org.sqlite.JDBC");
				connection = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath);
				connection.setReadOnly(readOnly.get());
			} catch (Exception e) {
				dab.writeError(e, "Database connection error.");
			}
		}
	}
	public synchronized void closeConnection() {
		try {
			if (connection == null) {return;}
			if (!connection.isClosed()) {
				connection.close();
			}
		} catch (Exception e) {
			dab.writeError(e, "Connection failed to close.");
		}
	}


	
}
