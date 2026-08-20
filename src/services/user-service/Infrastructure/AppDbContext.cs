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
            entity.Property(u => u.Phone).HasMaxLength(20);
            entity.Property(u => u.Name).HasMaxLength(200).IsRequired();
            entity.Property(u => u.PhotoUrl).HasMaxLength(1000);
            entity.Property(u => u.Gender).HasMaxLength(20).IsRequired();
            entity.Property(u => u.PreferredLanguage).HasMaxLength(10).IsRequired();
            entity.Property(u => u.Status).HasMaxLength(30).IsRequired();
            entity.Property(u => u.CreatedAt).HasColumnType("datetime2").IsRequired();

            // Constraints: google_id is the identity anchor for Google Sign-In —
            // one Google account maps to exactly one user row, enforced at the
            // DB layer so a race between two concurrent sign-in requests can't
            // create duplicate accounts for the same person.
            entity.HasIndex(u => u.GoogleId).IsUnique();

            // Index on phone: OTP verification looks users up by phone, not by
            // primary key, so that's the access pattern the index must serve.
            entity.HasIndex(u => u.Phone);
            entity.HasIndex(u => u.Status);
            entity.HasIndex(u => u.CreatedAt);
        });

        modelBuilder.Entity<OtpAttempt>(entity =>
        {
            entity.ToTable("otp_attempts");
            entity.HasKey(o => o.Id);
            entity.Property(o => o.Id).UseIdentityColumn(1, 1);

            entity.Property(o => o.Phone).HasMaxLength(20).IsRequired();
            entity.Property(o => o.AttemptCount).IsRequired().HasDefaultValue(0);
            entity.Property(o => o.LockedUntil).HasColumnType("datetime2");

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
