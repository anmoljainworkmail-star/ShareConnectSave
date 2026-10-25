package com.shareconnectsave.admin_service.dashboard;

// Dependency Inversion (SOLID-D) — DashboardStatsController depends on this
// interface, never DashboardStatsServiceImpl directly, same convention as
// ReviewQueueService/ReviewQueueController.
public interface DashboardStatsService {

    DashboardStatsResponse getStats();
}
