// Pattern: Configuration over hardcoding (the "Config" factor of the
// Twelve-Factor App methodology). A hardcoded hostname/port means every
// environment promotion (dev -> staging -> prod) requires a source change
// and a rebuild. Reading from process.env.* means one compiled artifact can
// be deployed anywhere by swapping environment variables at build time --
// no code change, no new PR, no risk of a dev URL leaking into a prod build.
//
// Mechanics note (why this is a BUILD-time read, not a runtime one): the
// browser has no Node `process` object. process.env.* here is textually
// replaced by esbuild's "define" step (configured per build configuration
// in angular.json) before the bundle ever reaches the browser -- it is not
// read live by the running app. There is deliberately no `?? fallback`
// literal here: `define` guarantees every process.env.* identifier below is
// always substituted with a real string for every configured build
// configuration, so a missing-value fallback would only hide a
// misconfigured angular.json rather than protect against anything real.
export const environment = {
  production: false,
  apiBase: process.env['API_BASE_URL'] as string,
  googleClientId: process.env['GOOGLE_CLIENT_ID'] as string,
  // T068: mirrors chat-service's CHAT_AUTO_CLOSE_THRESHOLD_HOURS (2h, in
  // minutes). Display-only -- it feeds the "Chat opened. You have 2 hours." and
  // "closes in 30 minutes" system messages; the SERVER alone decides when a
  // chat actually closes, so a drift here is cosmetic, never a behaviour bug.
  chatLifetimeMinutes: Number(process.env['CHAT_LIFETIME_MINUTES']),
  // Radar v2 (map). A style URL, not a hardcoded host in the component, so
  // switching tile provider (OpenFreeMap -> self-hosted) is a build setting.
  mapStyleUrl: process.env['MAP_STYLE_URL'] as string,
  // Where the map points when location permission is denied. Display-only.
  mapDefaultLat: Number(process.env['MAP_DEFAULT_LAT']),
  mapDefaultLng: Number(process.env['MAP_DEFAULT_LNG'])
};
