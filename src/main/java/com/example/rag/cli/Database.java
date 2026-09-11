package com.example.rag.cli;

import com.example.rag.AppConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** PostgreSQL JDBC 连接工厂。事务的提交与回滚由调用方负责。 */
public final class Database {
    private Database() {
    }

    public static Connection getConnection() throws SQLException {
        String host = AppConfig.getOrDefault("POSTGRES_HOST", "127.0.0.1");
        String port = AppConfig.getOrDefault("POSTGRES_PORT", "5432");
        String database = AppConfig.getOrDefault("POSTGRES_DB", "learning_app");
        String user = AppConfig.getOrDefault("POSTGRES_USER", "learning_app");
        String password = AppConfig.require("POSTGRES_PASSWORD");
        String url = "jdbc:postgresql://" + host + ":" + port + "/" + database;
        return DriverManager.getConnection(url, user, password);
    }
}
