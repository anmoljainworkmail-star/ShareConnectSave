import { ErrorEnvelope } from '../../auth/models/auth.models';

// Error envelope rule (CLAUDE.md): every backend failure is
// { code, message, traceId }; AdminService normalises transport failures into
// the same shape, so this is the one place a thrown value becomes screen text.
// `message` is the server's own sentence and is shown as-is; `code` only adds a
// hint where the code tells the admin something the message alone does not.
const PARTIAL_WRITE_HINT =
  'The action may be partly applied; the page was refreshed to show the real state.';

const HINTS_BY_CODE: Readonly<Record<string, string>> = {
  // admin-service answers this 503 when the suspend saga's connection step fails
  // (AdminExceptionHandler). The user IS suspended by then, so the hint must not
  // suggest retrying the suspend: the screen reconciles to "suspended" and only
  // Reinstate is offered. Telling the admin where to look is the only useful next step.
  CONNECTION_SERVICE_UNAVAILABLE:
    'The suspension was recorded but follow-up steps failed; check the user\'s connections.',
  // Trust-score override 503: the score may or may not have been stored, and the
  // page re-fetches to show which.
  TRUST_SCORE_UNAVAILABLE: PARTIAL_WRITE_HINT,
  FORBIDDEN: 'Your account does not have admin access.'
};

const FALLBACK_MESSAGE = 'Something went wrong. Please try again.';

export function describeAdminError(error: unknown): string {
  const envelope = error as Partial<ErrorEnvelope> | null;
  const message = envelope?.message || FALLBACK_MESSAGE;
  const hint = envelope?.code ? HINTS_BY_CODE[envelope.code] : undefined;
  return hint ? `${message} ${hint}` : message;
}
