import { defineSecret, defineString } from 'firebase-functions/params';

/**
 * Deploy-time configuration for the entity-sync functions.
 *
 * - GATEWAY_BASE_URL / HL_BASE_URL are public and set per environment.
 * - GATEWAY_ADMIN_TOKEN is a secret (Firebase Secret Manager) — never committed.
 * - The Firebase projectId comes free from the runtime (GCLOUD_PROJECT); we send it
 *   to the gateway's /internal/hl-token so no company id has to be configured here.
 */
export const GATEWAY_BASE_URL = defineString('GATEWAY_BASE_URL');
export const HL_BASE_URL = defineString('HL_BASE_URL');
export const GATEWAY_ADMIN_TOKEN = defineSecret('GATEWAY_ADMIN_TOKEN');
/**
 * The Humble Bill Engine's render URL (ticket #76). Lives in function config — NEVER in
 * client code: the endpoint is unauthenticated, so it must only ever be reached from a
 * Cloud Function.
 */
export const BILL_ENGINE_URL = defineString('BILL_ENGINE_URL');

export type SyncConfig = {
  gatewayBaseUrl: string;
  hlBaseUrl: string;
  adminToken: string;
  projectId: string;
  /** The bill-engine render endpoint (ticket #76). */
  billEngineUrl: string;
};

export function resolveConfig(): SyncConfig {
  return {
    gatewayBaseUrl: GATEWAY_BASE_URL.value(),
    hlBaseUrl: HL_BASE_URL.value(),
    adminToken: GATEWAY_ADMIN_TOKEN.value(),
    projectId: process.env.GCLOUD_PROJECT ?? process.env.GOOGLE_CLOUD_PROJECT ?? '',
    billEngineUrl: BILL_ENGINE_URL.value(),
  };
}
