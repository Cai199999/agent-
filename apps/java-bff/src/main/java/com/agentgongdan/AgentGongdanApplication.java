package com.agentgongdan;

import com.agentgongdan.config.AgentStandalonePortGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AgentGongdanApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(AgentGongdanApplication.class);
        application.addListeners(new AgentStandalonePortGuard());
        application.run(args);
    }
}
