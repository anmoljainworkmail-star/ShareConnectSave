// Wire DTOs for admin-service's own outward contracts (ReviewQueueItem,
// UserDetailResponse, DashboardStatsResponse, SuspendResponse, ...). Unlike
// user-service's snake_case wire shapes, admin-service deliberately serialises
// plain camelCase (see the header comments on its record DTOs), so these names
// are camelCase on purpose -- written down explicitly, never guessed, so a
// contract drift is a compile-time mismatch instead of a silently-undefined field.
//
// The recurring "...Unavailable" booleans are the backend's partial-failure
// markers: when ONE upstream service (User / Rating / Report) is down, the
// backend degrades only that slice and sets the flag, leaving the value null /
// empty / 0. Plain English: a "0 reports" next to reportHistoryUnavailable=true
// does NOT mean "no reports" -- it means "we could not find out". Every screen
// must therefore branch on the flag BEFORE reading the value.

export interface ReviewQueueItem {
  userId: string;
  displayName: string;
  status: string;
  trustScore: number | null;
  trustScoreUnavailable: boolean;
  reportCount: number | null;
  reportCountUnavailable: boolean;
  suspendedAt: string | null;
}

export interface ReportHistoryEntry {
  id: string;
  reporterId: string;
  reason: string;
  status: string;
  createdAt: string;
}

export interface UserDetail {
  userId: string;
  // null (with profileUnavailable) when User Service could not be reached.
  displayName: string | null;
  status: string | null;
  profileUnavailable: boolean;
  trustScore: number | null;
  trustScoreUnavailable: boolean;
  reportHistory: ReportHistoryEntry[];
  reportCount: number;
  reportHistoryUnavailable: boolean;
}

export interface TopReportedUserSummary {
  reportedId: string;
  reportCount: number;
}

export interface DashboardStats {
  totalActiveUsers: number | null;
  totalActiveUsersUnavailable: boolean;
  totalScansToday: number | null;
  totalScansTodayUnavailable: boolean;
  totalConnectionsAcceptedToday: number | null;
  totalConnectionsAcceptedTodayUnavailable: boolean;
  totalReportsToday: number | null;
  totalReportsTodayUnavailable: boolean;
  topReportedUsers: TopReportedUserSummary[];
  topReportedUsersUnavailable: boolean;
}

export interface SuspendResponse {
  userId: string;
  status: string;
  expiredConnectionCount: number;
  updatedAt: string;
}

export interface ReinstateResponse {
  userId: string;
  status: string;
  updatedAt: string;
}

export interface TrustScoreOverrideResponse {
  userId: string;
  score: number;
  reason: string;
  auditId: string;
  overriddenBy: string;
  overriddenAt: string;
}

// admin-service's account-status wire values (ReviewQueueServiceImpl's
// STATUS_SUSPENDED / STATUS_ACTIVE). Typed as plain strings on the DTOs above
// because the backend can add states; these constants are only the two this UI
// branches on.
export const STATUS_SUSPENDED = 'suspended';
export const STATUS_ACTIVE = 'active';
