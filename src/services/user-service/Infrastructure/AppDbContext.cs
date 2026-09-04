using Microsoft.EntityFrameworkCore;
using user_service.Models;

namespace user_service.Infrastructure;

public class AppDbContext : DbContext
{
    public AppDbContext(DbContextOptions<AppDbContext> options) : base(options)
    {
    }

    public DbSet<User> Users => Set<User>();
    public DbSet<OtpAttempt> OtpAttempts => Set<OtpAttempt>();
    public DbSet<IdentityVerification> IdentityVerifications => Set<IdentityVerification>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        base.OnModelCreating(modelBuilder);

        modelBuilder.Entity<User>(entity =>
        {
            entity.ToTable("users");
            entity.HasKey(u => u.Id);
            entity.Property(u => u.Id).UseIdentityColumn(1, 1);

            entity.Property(u => u.GoogleId).HasMaxLength(255).IsRequired();
            entity.Property(u => u.Email).HasMaxLength(320);
            entity.Property(u => u.Phone).HasMaxLength(20);
            entity.Property(u => u.Name).HasMaxLength(200).IsRequired();
            entity.Property(u => u.PhotoUrl).HasMaxLength(1000);
            entity.Property(u => u.Gender).HasMaxLength(20).IsRequired();
            entity.Property(u => u.PreferredLanguage).HasMaxLength(10).IsRequired();
            entity.Property(u => u.Status).HasMaxLength(30).IsRequired();
            entity.Property(u => u.CreatedAt).HasColumnType("datetime2").IsRequired();

            // Post-T018 fix: split out of Status — see User.IsOnboardingComplete's
            // class comment. Defaults false at the DB level too, matching the
            // entity default, so a row inserted by anything other than EF Core
            // (a manual script, a future data migration) can't accidentally
            // default to "onboarding already complete".
            entity.Property(u => u.IsOnboardingComplete).IsRequired().HasDefaultValue(false);

            // T019: same reasoning as IsOnboardingComplete immediately above -
            // required + defaults to false at the DB level too, so a row
            // inserted by anything other than EF Core can't accidentally
            // default to "already verified".
            entity.Property(u => u.IdentityBadge).IsRequired().HasDefaultValue(false);

            // T017: nullable — "never verified" is a real, common state
            // (every new user, until they pass OTP), not an absence of data.
            entity.Property(u => u.PhoneVerifiedAt).HasColumnType("datetime2");

            // Optimistic Concurrency Token (see User.RowVersion's class
            // comment for the full "why"): IsRowVersion() tells EF Core this
            // column is a SQL Server `rowversion` — include it in every
            // UPDATE's WHERE clause and throw DbUpdateConcurrencyException
            // when the row changed since it was read, instead of the update
            // silently applying anyway.
            entity.Property(u => u.RowVersion).IsRowVersion();

            // Constraints: google_id is the identity anchor for Google Sign-In —
            // one Google account maps to exactly one user row, enforced at the
            // DB layer so a race between two concurrent sign-in requests can't
            // create duplicate accounts for the same person.
            entity.HasIndex(u => u.GoogleId).IsUnique();

            // Index on phone: OTP verification looks users up by phone, not by
            // primary key, so that's the access pattern the index must serve.
            //
            // Filtered Unique Index (fix): a phone number identifies one real
            // person — once VERIFIED on one account it must never also be
            // verified on a second, different account. OtpService.VerifyOtpAsync
            // already runs an application-level SELECT-then-check for this
            // (a fast-path that avoids a DB round trip in the common case),
            // but a check-then-act check alone is not race-safe — two
            // concurrent successful verifications for the same phone can both
            // pass the SELECT before either commits. The filter
            // (PhoneVerifiedAt IS NOT NULL) is what makes this a REAL
            // guarantee instead of an optimization: unverified rows (Phone
            // set to null/pending, or never verified) are explicitly allowed
            // to collide, only two VERIFIED rows for the same phone collide.
            entity.HasIndex(u => u.Phone).IsUnique().HasFilter("[PhoneVerifiedAt] IS NOT NULL");
            entity.HasIndex(u => u.Status);
            entity.HasIndex(u => u.CreatedAt);

            // Post-T018 fix: Discovery Service (later phases) needs "who is
            // eligible to appear in a scan" to be an efficient lookup —
            // exactly the query IsOnboardingComplete now answers, since
            // Status no longer carries that signal.
            entity.HasIndex(u => u.IsOnboardingComplete);

            // T019: same reasoning as the IsOnboardingComplete index above -
            // Discovery Service (T020+) and any future "Trusted"-badge
            // filter/ranking need "who is identity-verified" to be an
            // efficient lookup, not a table scan, the moment they start
            // reading this column.
            entity.HasIndex(u => u.IdentityBadge);
        });

        modelBuilder.Entity<OtpAttempt>(entity =>
        {
            entity.ToTable("otp_attempts");
            entity.HasKey(o => o.Id);
            entity.Property(o => o.Id).UseIdentityColumn(1, 1);

            entity.Property(o => o.Phone).HasMaxLength(20).IsRequired();
            entity.Property(o => o.AttemptCount).IsRequired().HasDefaultValue(0);
            entity.Property(o => o.LockedUntil).HasColumnType("datetime2");

            // T017: WindowStartedAt anchors the "5 failures within 10
            // minutes" rule; CodeHash/CodeCreatedAt/CodeExpiresAt hold the
            // currently outstanding code (see OtpAttempt's class comment for
            // why hash-only, never plaintext).
            entity.Property(o => o.WindowStartedAt).HasColumnType("datetime2");
            entity.Property(o => o.CodeHash).HasMaxLength(64);
            entity.Property(o => o.CodeCreatedAt).HasColumnType("datetime2");
            entity.Property(o => o.CodeExpiresAt).HasColumnType("datetime2");

            // Optimistic Concurrency Token (see OtpAttempt.RowVersion's class
            // comment for the full "why"): IsRowVersion() tells EF Core this
            // column is a SQL Server `rowversion` — include it in every
            // UPDATE's WHERE clause and throw DbUpdateConcurrencyException
            // when the row changed since it was read, instead of the update
            // silently applying anyway.
            entity.Property(o => o.RowVersion).IsRowVersion();

            // Every OTP request looks up "attempts for this phone" first —
            // the index makes that lockout check O(log n) instead of a scan.
            entity.HasIndex(o => o.Phone).IsUnique();
            entity.HasIndex(o => o.LockedUntil);
        });

        modelBuilder.Entity<IdentityVerification>(entity =>
        {
            entity.ToTable("identity_verifications");
            entity.HasKey(v => v.Id);
            entity.Property(v => v.Id).UseIdentityColumn(1, 1);

            entity.Property(v => v.Status).HasMaxLength(30).IsRequired();
            entity.Property(v => v.VerifiedAt).HasColumnType("datetime2");

            entity.HasOne(v => v.User)
                .WithMany()
                .HasForeignKey(v => v.UserId)
                // ON DELETE CASCADE: a verification record has no meaning once
                // its user is gone — deleting the user should not require a
                // separate cleanup step or leave an orphaned row behind.
                .OnDelete(DeleteBehavior.Cascade);

            entity.HasIndex(v => v.UserId);
            entity.HasIndex(v => v.Status);
            entity.HasIndex(v => v.VerifiedAt);
        });
    }
}
