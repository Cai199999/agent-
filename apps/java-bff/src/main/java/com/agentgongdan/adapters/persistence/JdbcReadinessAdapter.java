package com.agentgongdan.adapters.persistence;

import com.agentgongdan.app.ports.health.ReadinessPort;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 数据库就绪检查：只做连接有效性校验，不查询业务表。
 */
@Component
public class JdbcReadinessAdapter implements ReadinessPort {

    private final DataSource dataSource;

    public JdbcReadinessAdapter(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public boolean databaseReady() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }
}
