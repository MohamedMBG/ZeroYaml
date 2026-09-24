# GitHub Webhook Ingress

The Control Plane receives GitHub webhook deliveries on one HTTP endpoint. The
endpoint is an ingress adapter: it checks the delivery envelope, applies the
supported event allow-list, and hands supported deliveries to a downstream
handler. It does not parse payloads, verify signatures, or start pipeline work.

## Route

```text
POST /webhooks/github
```

The route is served on the Control Plane HTTP port (Spring Boot `server.port`,
default `8080`). In GitHub, set the webhook **Payload URL** to
`https://<control-plane-host>/webhooks/github` and the **Content type** to
`application/json`. Form-encoded deliveries are rejected.

## Envelope validation

Checks run in this order, and the first failure decides the answer:

| Check | Failure |
| --- | --- |
| `X-GitHub-Event` is present and a lower-case event name (`[a-z][a-z_]{0,63}`) | `400` |
| `X-GitHub-Delivery` is present and a delivery identifier (`[A-Za-z0-9-]{1,64}`) | `400` |
| `Content-Type` is `application/json`, with or without parameters | `415` |
| Declared and actual body size are within `zeroyaml.github.webhook.max-payload-size` | `413` |
| The first non-whitespace byte of the body opens a JSON object | `400` |

The body is read at most one byte past the limit, so an oversized or chunked
request cannot claim more memory than the limit allows. Full JSON parsing is
left to event normalization.

## Event allow-list

| Event | Status | Outcome | Downstream handler called |
| --- | --- | --- | --- |
| `push` | `202 Accepted` | `ACCEPTED` | yes |
| `ping` | `200 OK` | `IGNORED` | no |
| any other event | `200 OK` | `IGNORED` | no |

GitHub sends `ping` once when a webhook is created. Unsupported events are
answered with a `2xx` status because a well-formed delivery the Control Plane
chooses not to act on is not a failure; an error status would mark it as failed
in GitHub's **Recent Deliveries** view.

## Response body

Every answer, including rejections, has the same shape:

```json
{
  "deliveryId": "72d3162e-cc78-11e3-81ab-4c9367dc0958",
  "event": "push",
  "outcome": "ACCEPTED",
  "message": "delivery accepted for processing"
}
```

`deliveryId` and `event` are `null` when their header failed validation.
Messages never echo header values or payload content, because both are
untrusted until the signature is verified.

## Downstream hand-off

A supported delivery is passed to `GitHubWebhookDeliveryHandler` as a
`GitHubWebhookDelivery`, which keeps:

- the raw body byte-for-byte, because signature verification computes an HMAC
  over the exact bytes GitHub sent;
- `X-GitHub-Delivery` and `X-GitHub-Event`;
- `X-Hub-Signature-256`, unverified;
- `X-GitHub-Hook-ID`, `X-GitHub-Hook-Installation-Target-Type`, and
  `X-GitHub-Hook-Installation-Target-ID` when present. Optional headers longer
  than 256 characters are dropped.

The handler runs on the request thread before GitHub receives its answer, so an
implementation must return quickly. The current handler only writes an audit
log record. Signature verification (#17) and event normalization (#20) are
implemented behind this boundary.

## Configuration

| Property | Default | Purpose |
| --- | --- | --- |
| `zeroyaml.github.webhook.max-payload-size` | `5MB` | Largest accepted payload, between 1 byte and GitHub's 25 MB cap |

Override it with `ZEROYAML_GITHUB_WEBHOOK_MAX_PAYLOAD_SIZE`. A value outside the
range fails startup.

## Logging

Each delivery writes one log record with the delivery identifier, the event,
and the outcome. Accepted deliveries add the payload size, and rejections add
the status and reason. Records never contain the payload or the signature.

## Current limits

- Signatures are not verified yet, so the endpoint must not be exposed where
  untrusted callers can reach it until #17 lands.
- Accepted deliveries are not processed further until event normalization (#20)
  exists.
- Redeliveries are not deduplicated; the delivery identifier is preserved so a
  downstream consumer can deduplicate.
