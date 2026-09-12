package com.emergentterrain.researchservice;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Scheduling belongs to the continuous worker, never a finite maintenance command. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "app.research-service.scheduling.enabled", havingValue = "true",
        matchIfMissing = true)
class ResearchServiceSchedulingConfiguration {

    /** Keeps the ownership worker from being starved by long-running discovery schedules. */
    @Bean(name = "researchWorkerTaskScheduler")
    TaskScheduler researchWorkerTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("research-worker-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        return scheduler;
    }
}
