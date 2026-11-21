// Devil Cam: Alexa smart home skill (AWS Lambda, Node.js 20, no extra packages).
//
// Lets you say "Alexa, show Camera 1" and see it on an Echo Show or Fire TV.
// The video comes from the Devil Cam Hub at home, through its secure Cloudflare address.
//
// Settings (Lambda > Configuration > Environment variables):
//   HUB_URL        https://cam.devilmediagroup.com   (the hub's Cloudflare address)
//   HUB_SECRET     the key printed by the hub installer
//   ALLOWED_EMAILS your Amazon account email(s), comma separated. Only these accounts can use the skill.

const HUB_URL = (process.env.HUB_URL || "").replace(/\/+$/, "");
const HUB_SECRET = process.env.HUB_SECRET || "";
const ALLOWED = (process.env.ALLOWED_EMAILS || "")
  .split(",").map((s) => s.trim().toLowerCase()).filter(Boolean);

const hub = (path) => `${HUB_URL}/${encodeURIComponent(HUB_SECRET)}${path}`;
const uuid = () => crypto.randomUUID();

/** Checks the person linking the skill is allowed (Login with Amazon token -> email). */
async function allowed(token) {
  if (!token) return false;
  if (ALLOWED.length === 0) return true;
  try {
    const r = await fetch("https://api.amazon.com/user/profile", {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!r.ok) return false;
    const p = await r.json();
    return ALLOWED.includes(String(p.email || "").toLowerCase());
  } catch {
    return false;
  }
}

function tokenOf(d) {
  return d?.endpoint?.scope?.token || d?.payload?.scope?.token || d?.payload?.grantee?.token || null;
}

/** The cameras the hub knows about, e.g. ["Camera 1"]. */
async function cameras() {
  const r = await fetch(hub("/api/streams"), { signal: AbortSignal.timeout(4000) });
  if (!r.ok) throw new Error(`hub answered ${r.status}`);
  return Object.keys(await r.json());
}

const idFor = (name) => "devilcam-" + Buffer.from(name).toString("base64url");
const nameFor = (id) => Buffer.from(id.replace(/^devilcam-/, ""), "base64url").toString();

function errorResponse(d, type, message) {
  return {
    event: {
      header: { namespace: "Alexa", name: "ErrorResponse", payloadVersion: "3", messageId: uuid(),
        correlationToken: d.header.correlationToken },
      endpoint: d.endpoint ? { endpointId: d.endpoint.endpointId } : undefined,
      payload: { type, message },
    },
  };
}

function healthProperty() {
  return { namespace: "Alexa.EndpointHealth", name: "connectivity", value: { value: "OK" },
    timeOfSample: new Date().toISOString(), uncertaintyInMilliseconds: 0 };
}

async function discover(d) {
  let names = [];
  try { names = await cameras(); } catch (e) { console.log("Hub not reachable:", e.message); }
  const endpoints = names.map((name) => ({
    endpointId: idFor(name),
    manufacturerName: "Devil Media Group",
    friendlyName: name,
    description: "Devil Cam camera",
    displayCategories: ["CAMERA"],
    capabilities: [
      {
        type: "AlexaInterface", interface: "Alexa.CameraStreamController", version: "3",
        cameraStreamConfigurations: [{
          protocols: ["HLS"],
          resolutions: [{ width: 1920, height: 1080 }, { width: 1280, height: 720 }],
          authorizationTypes: ["NONE"],
          videoCodecs: ["H264"],
          audioCodecs: ["NONE"],
        }],
      },
      { type: "AlexaInterface", interface: "Alexa.EndpointHealth", version: "3",
        properties: { supported: [{ name: "connectivity" }], proactivelyReported: false, retrievable: true } },
      { type: "AlexaInterface", interface: "Alexa", version: "3" },
    ],
  }));
  return {
    event: {
      header: { namespace: "Alexa.Discovery", name: "Discover.Response", payloadVersion: "3", messageId: uuid() },
      payload: { endpoints },
    },
  };
}

function initStreams(d) {
  const name = nameFor(d.endpoint.endpointId);
  const src = encodeURIComponent(name);
  const expires = new Date(Date.now() + 60 * 60 * 1000).toISOString();
  return {
    event: {
      header: { namespace: "Alexa.CameraStreamController", name: "Response", payloadVersion: "3",
        messageId: uuid(), correlationToken: d.header.correlationToken },
      endpoint: { endpointId: d.endpoint.endpointId },
      payload: {
        cameraStreams: [{
          uri: hub(`/api/stream.m3u8?src=${src}`),
          expirationTime: expires,
          idleTimeoutSeconds: 30,
          protocol: "HLS",
          resolution: { width: 1920, height: 1080 },
          authorizationType: "NONE",
          videoCodec: "H264",
          audioCodec: "NONE",
        }],
        imageUri: hub(`/api/frame.jpeg?src=${src}`),
      },
    },
    context: { properties: [healthProperty()] },
  };
}

function stateReport(d) {
  return {
    event: {
      header: { namespace: "Alexa", name: "StateReport", payloadVersion: "3", messageId: uuid(),
        correlationToken: d.header.correlationToken },
      endpoint: { endpointId: d.endpoint.endpointId },
      payload: {},
    },
    context: { properties: [healthProperty()] },
  };
}

export const handler = async (event) => {
  const d = event.directive;
  if (!d) return {};
  const { namespace, name } = d.header;
  console.log("Directive:", namespace, name);

  if (!(await allowed(tokenOf(d)))) {
    if (namespace === "Alexa.Authorization") {
      return { event: { header: { namespace: "Alexa.Authorization", name: "ErrorResponse", payloadVersion: "3", messageId: uuid() },
        payload: { type: "ACCEPT_GRANT_FAILED", message: "This Amazon account isn't allowed to use Devil Cam." } } };
    }
    return errorResponse(d, "INVALID_AUTHORIZATION_CREDENTIAL", "This Amazon account isn't allowed to use Devil Cam.");
  }

  if (namespace === "Alexa.Authorization" && name === "AcceptGrant") {
    return { event: { header: { namespace: "Alexa.Authorization", name: "AcceptGrant.Response", payloadVersion: "3", messageId: uuid() }, payload: {} } };
  }
  if (namespace === "Alexa.Discovery" && name === "Discover") return discover(d);
  if (namespace === "Alexa.CameraStreamController" && name === "InitializeCameraStreams") return initStreams(d);
  if (namespace === "Alexa" && name === "ReportState") return stateReport(d);
  return errorResponse(d, "INVALID_DIRECTIVE", `Devil Cam doesn't handle ${namespace}.${name}`);
};
