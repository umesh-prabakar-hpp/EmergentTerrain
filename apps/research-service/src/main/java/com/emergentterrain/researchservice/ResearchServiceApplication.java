package com.emergentterrain.researchservice;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Research-only runtime. It deliberately does not scan controllers, web sockets, GPU adapters,
 * world generation, or RoomService; the game process is unnecessary for autonomous research.
 */
@SpringBootApplication(scanBasePackages = "com.emergentterrain.researchservice")
@ComponentScan(
        basePackages = {
                "com.emergentterrain.researchservice",
                "com.emergentterrain.research",
                "com.emergentterrain.llm",
                "com.emergentterrain.secrets",
                "com.emergentterrain.service"
        },
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.emergentterrain\\.service\\.(RoomService|WorldService|BotMovementService|"
                        + "BotSimulationGpuService|BotArchetypeBehaviorService|HighwayEmergenceService|"
                        + "MapIndexService|OccupancyService|PayloadService|PolicyConsultationService|"
                        + "ProfileService|QrService|RoomProposalService|SnapshotTokenService|"
                        + "TileValidatorService|UserService|ExplanationService)"))
@EntityScan(basePackages = {
        "com.emergentterrain.domain",
        "com.emergentterrain.research",
        "com.emergentterrain.researchservice"
})
@EnableJpaRepositories(basePackages = {
        "com.emergentterrain.repo",
        "com.emergentterrain.research",
        "com.emergentterrain.researchservice"
})
@EnableScheduling
public class ResearchServiceApplication {

    /** Keeps the ownership worker from being starved by long-running discovery schedules. */
    @Bean(name = "researchWorkerTaskScheduler")
    public TaskScheduler researchWorkerTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("research-worker-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        return scheduler;
    }

    public static void main(String[] args) {
        SpringApplication.run(ResearchServiceApplication.class, args);
    }
}
