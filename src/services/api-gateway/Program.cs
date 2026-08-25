var builder = WebApplication.CreateBuilder(args);

// No Hardcoded Config: builder.Configuration already reads appsettings.json,
// appsettings.{Environment}.json and environment variables in that order, so
// Docker Compose can override any ReverseProxy value at deploy time without a
// code change or rebuild.
builder.Configuration.AddEnvironmentVariables();

// API Gateway pattern + Dependency Inversion (D in SOLID): the gateway depends
// on the *shape* of a route/cluster config, not on hardcoded knowledge of
// where each of the 8 downstream services lives. LoadFromConfig binds the
// "ReverseProxy" section (Routes + Clusters) from appsettings.json. Adding a
// 9th service later means adding config, not touching this file — that's the
// Open/Closed Principle in practice: the routing engine here never changes.
builder.Services.AddReverseProxy()
    .LoadFromConfig(builder.Configuration.GetSection("ReverseProxy"));

var app = builder.Build();

// Config-driven proxying: every request either matches one of the 8 routes in
// appsettings.json and gets forwarded to its cluster, or matches nothing and
// falls through to ASP.NET Core's default 404 — no MapFallback/catch-all is
// registered here on purpose, so unmatched routes aren't swallowed before
// YARP (or the framework) gets to return that 404.
app.MapReverseProxy();

app.Run();
