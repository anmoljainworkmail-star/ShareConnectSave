// Pattern: Angular environment file replacement (build-time polymorphism).
// angular.json's "production" build configuration declares a
// `fileReplacements` entry that swaps this file in for environment.ts --
// every other file in the app imports from './environments/environment' and
// never knows which concrete file backed that path for a given build. Same
// shape as Dependency Inversion at the file-system level: code depends on a
// stable name (an abstraction), not on which implementation satisfies it.
//
// See environment.ts for why these are process.env.* reads rather than
// literals, and why that substitution must happen at build time.
export const environment = {
  production: true,
  apiBase: process.env['API_BASE_URL'] as string,
  googleClientId: process.env['GOOGLE_CLIENT_ID'] as string
};
