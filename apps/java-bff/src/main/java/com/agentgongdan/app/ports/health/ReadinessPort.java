package com.agentgongdan.app.ports.health;

/**
 * 就绪检查端口：屏蔽具体数据源/连接池实现，便于替换为健康检查组件。
 */
public interface ReadinessPort {

    boolean databaseReady();
}
