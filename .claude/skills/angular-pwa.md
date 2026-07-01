# Angular PWA Skill — ShareConnectSave Frontend

You are implementing the Angular 17 frontend for ShareConnectSave. This is a learning project — add short comments naming the pattern being used (not what the code does).

## Non-negotiable rules

- **No standalone components.** Every component, directive, pipe, and service belongs to an NgModule.
- **Feature modules are lazy-loaded.** Every route uses `loadChildren: () => import('./feature/feature.module').then(m => m.FeatureModule)`.
- **Shared UI only in SharedModule.** Feature-specific logic never leaks into SharedModule.
- **NgModule structure per feature:**
  ```
  feature/
    feature.module.ts       ← declares components, imports SharedModule
    feature-routing.module.ts
    components/
    services/
    models/
  ```

## State management

No NgRx. Use Angular services with `BehaviorSubject` for shared state. Local component state is plain class properties. `async` pipe in templates over manual `subscribe`.

## HTTP

All API calls go through a service layer — never call `HttpClient` directly from a component.

```typescript
// AuthInterceptor — adds JWT to every outbound request (DI, no manual header per call)
@Injectable()
export class AuthInterceptor implements HttpInterceptor {
  intercept(req: HttpRequest<any>, next: HttpHandler): Observable<HttpEvent<any>> {
    const token = this.authService.getToken();
    if (!token) return next.handle(req);
    // Pattern: immutable clone — HttpRequest is frozen after construction
    return next.handle(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }));
  }
}
```

Register in `AppModule` providers: `{ provide: HTTP_INTERCEPTORS, useClass: AuthInterceptor, multi: true }`.

## SignalR client

Use `@microsoft/signalr` package. Create a service per hub — never instantiate `HubConnection` in a component.

```typescript
// Pattern: connection lifecycle owned by service, not component
// Reason: components mount/unmount but the connection should persist across navigation
@Injectable({ providedIn: 'root' })
export class ChatHubService {
  private connection: HubConnection;

  connect(chatId: string): void {
    this.connection = new HubConnectionBuilder()
      .withUrl(`${environment.apiBase}/hubs/chat`, { accessTokenFactory: () => this.authService.getToken() })
      .withAutomaticReconnect()  // handles transient disconnects without user action
      .build();
    this.connection.start();
  }

  onMessage(): Observable<ChatMessage> {
    return new Observable(observer =>
      this.connection.on('ReceiveMessage', msg => observer.next(msg))
    );
  }
}
```

## Radar UI

The radar is an SVG element with CSS animations — not a canvas. The sweep is a `conic-gradient` CSS trick applied to a pseudo-element that rotates via `@keyframes`.

Key points:
- Rings are `<circle>` elements in SVG with `stroke: violet`, `fill: none`
- User dots are `<foreignObject>` containing an `<img>` (avatar) + a badge `<span>` for verified users
- Animation uses CSS `animation` on the SVG wrapper — not JavaScript `requestAnimationFrame`
- The phosphor trail is a second element with lower opacity that lags 0.5s behind the sweep

## Web Bluetooth API

Only available on Android Chrome. Always check before calling:

```typescript
// Pattern: feature detection over user-agent sniffing
// Reason: UA strings are unreliable; capability check is the standard approach
if (!navigator.bluetooth) {
  this.showBleUnavailable(); // iOS or unsupported browser
  return;
}
const device = await navigator.bluetooth.requestDevice({
  filters: [{ services: [BLE_SERVICE_UUID] }]
});
```

The `BLE_SERVICE_UUID` is a constant UUID registered for this app — never a user-level identifier.

## PWA / Service Worker

- Config: `ngsw-config.json` defines caching strategy
- Static screens (profile, settings) use `CacheFirst` — served offline
- API calls use `NetworkFirst` with fallback to cached response
- Never cache: `/discovery/*`, `/chat/*` — these need live data

```json
{
  "dataGroups": [
    {
      "name": "api-freshness",
      "urls": ["/api/**"],
      "cacheConfig": { "strategy": "freshness", "timeout": "5s", "maxAge": "1h" }
    }
  ]
}
```

## Auth guard

```typescript
@Injectable({ providedIn: 'root' })
export class AuthGuard implements CanActivate {
  canActivate(): boolean | UrlTree {
    // Guard clause: fail fast, redirect immediately
    if (!this.authService.isAuthenticated()) {
      return this.router.createUrlTree(['/auth/login']);
    }
    return true;
  }
}
```

## Color tokens (use CSS variables — never hardcode hex in TypeScript)

```scss
// All colours come from the design system tokens in styles/tokens.scss
// Never reference #7C3AED directly in component SCSS — always var(--color-primary)
.btn-primary { background: var(--gradient-primary-btn); }
.status-looking { background: var(--color-primary-light); color: var(--color-primary-dark); }
```

## Environment config

```typescript
// Never hardcode URLs — all config from environment files
// environment.ts for dev, environment.prod.ts for prod
export const environment = {
  production: false,
  apiBase: 'http://localhost:8080',
  googleClientId: process.env['GOOGLE_CLIENT_ID'] ?? ''
};
```

## Routing structure

```
AppRoutingModule (root, eager):
  /auth → AuthModule (lazy) → login, otp, profile-setup, identity-verify
  /radar → RadarModule (lazy) → home, filters
  /activity → ActivityModule (lazy) → feed
  /chat/:id → ChatModule (lazy) → chat-room
  /profile → ProfileModule (lazy) → view, edit, settings, block-list
  /admin → AdminModule (lazy, AdminGuard) → queue, user-detail, stats
```
