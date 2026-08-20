/**
 * Thin client for the Humble Bill Engine (ticket #76) — the shared AWS Lambda that renders a
 * branded PDF invoice from a payload and returns a public, permanent S3 URL.
 *
 * ⚠️ We NEVER touch the engine itself (template, `billApps/aromex`, bucket). This file only
 * builds nothing — it just POSTs a caller-built payload and returns the URL.
 *
 * The endpoint is UNAUTHENTICATED, which is exactly why it may only be reached from a Cloud
 * Function (URL in function config), never from a device.
 */

/** How long to wait for the engine — generous, because a cold Lambda start is slow. */
const RENDER_TIMEOUT_MS = 30_000;

/**
 * A bill-engine render failure. Carries the engine's own `details` (when it returned any) so
 * the reason survives onto the sale's `invoiceError` for diagnosis and reconcile retries.
 */
export class BillEngineError extends Error {
  constructor(
    message: string,
    readonly details?: unknown,
  ) {
    super(message);
    this.name = 'BillEngineError';
  }
}

/** The engine's success body: a human `message` and the permanent, public PDF `url`. */
type RenderResponse = { message?: string; url?: string; details?: unknown; error?: string };

/**
 * Render one invoice: POST [payload] to [billEngineUrl] and return the PDF URL.
 *
 * Success is **HTTP 200 AND a `url` in the body** — anything else (non-200, missing `url`,
 * timeout, network error) throws [BillEngineError]. The URL is public and permanent, so the
 * caller stores it verbatim.
 */
export async function renderInvoice(billEngineUrl: string, payload: unknown): Promise<string> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), RENDER_TIMEOUT_MS);
  let res: Response;
  try {
    res = await fetch(billEngineUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      signal: controller.signal,
    });
  } catch (err) {
    // Aborts (timeout) and network errors both land here.
    throw new BillEngineError(`bill engine request failed: ${(err as Error)?.message ?? err}`);
  } finally {
    clearTimeout(timer);
  }

  let body: RenderResponse;
  try {
    body = (await res.json()) as RenderResponse;
  } catch {
    body = {};
  }

  if (res.status !== 200) {
    throw new BillEngineError(
      `bill engine → HTTP ${res.status}${body.error ? `: ${body.error}` : ''}`,
      body.details ?? body,
    );
  }
  if (!body.url) {
    // A 200 without a url is still a failure (ticket #76).
    throw new BillEngineError('bill engine returned 200 but no url', body.details ?? body);
  }
  return body.url;
}
