import { IRetryPolicy, RetryContext } from '@microsoft/signalr';

// Reconnect schedule (milliseconds), shared by every hub connection in the app
// (NotificationService since T067, ChatSignalrService since T068). SignalR's
// built-in default gives up after four attempts (~42 seconds total) and then
// the connection is dead until the page reloads -- a phone that walks through
// a tunnel or lets the tab sleep for a minute would silently stop receiving
// pushes forever. So both hubs need an IRetryPolicy that never returns null:
// quick first retries for a blip, then a steady 30 s heartbeat for a long
// outage.
//
// Extracted into one file (DRY + Single Responsibility): "how patiently should
// this app reconnect" is one decision. Two private copies would let the two
// hubs drift apart -- e.g. chat giving up after a minute while notifications
// keep trying -- which is exactly the kind of inconsistency users read as
// "the app is flaky".
export const RECONNECT_DELAYS_MS = [0, 2_000, 5_000, 10_000] as const;
export const RECONNECT_STEADY_DELAY_MS = 30_000;

// Backoff-with-a-ceiling: never hammer a struggling server (delay grows),
// never give up either (delay stops growing, retries continue).
export class UnlimitedRetryPolicy implements IRetryPolicy {
  nextRetryDelayInMilliseconds(retryContext: RetryContext): number {
    return RECONNECT_DELAYS_MS[retryContext.previousRetryCount] ?? RECONNECT_STEADY_DELAY_MS;
  }
}
