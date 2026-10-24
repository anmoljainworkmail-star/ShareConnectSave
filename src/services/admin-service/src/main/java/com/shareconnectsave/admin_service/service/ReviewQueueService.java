package com.shareconnectsave.admin_service.service;

import java.util.List;

import com.shareconnectsave.admin_service.dto.ReinstateResponse;
import com.shareconnectsave.admin_service.dto.ReviewQueueItem;
import com.shareconnectsave.admin_service.dto.UserDetailResponse;

// Dependency Inversion (SOLID-D): ReviewQueueController depends on this
// interface, never on ReviewQueueServiceImpl — same "thin controller, real
// logic behind an interface" convention every other Java service in this
// platform follows (ConnectionService, ReportService, TrustScoreReadService).
public interface ReviewQueueService {

    List<ReviewQueueItem> getQueue();

    UserDetailResponse getUserDetail(String userId);

    ReinstateResponse reinstate(String userId);
}
