export interface Env {
  ROOM: DurableObjectNamespace;
  RELAY_ADMIN_TOKEN?: string;
  /** Optional. Where car.say messages are POSTed. https only (http allowed for localhost). */
  CAR_WEBHOOK_URL?: string;
  /** Optional. Secret sent to the webhook in the auth header. */
  CAR_WEBHOOK_KEY?: string;
  /** Optional. Header that carries the key. Default "Authorization". */
  CAR_WEBHOOK_AUTH_HEADER?: string;
  /** Optional. Word placed before the key. Default "Bearer". Set to "none" to send the raw key. */
  CAR_WEBHOOK_AUTH_SCHEME?: string;
}
