using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace user_service.Migrations
{
    /// <inheritdoc />
    public partial class AddOtpVerificationFields : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<DateTime>(
                name: "PhoneVerifiedAt",
                table: "users",
                type: "datetime2",
                nullable: true);

            migrationBuilder.AddColumn<DateTime>(
                name: "CodeCreatedAt",
                table: "otp_attempts",
                type: "datetime2",
                nullable: true);

            migrationBuilder.AddColumn<DateTime>(
                name: "CodeExpiresAt",
                table: "otp_attempts",
                type: "datetime2",
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "CodeHash",
                table: "otp_attempts",
                type: "nvarchar(64)",
                maxLength: 64,
                nullable: true);

            migrationBuilder.AddColumn<DateTime>(
                name: "WindowStartedAt",
                table: "otp_attempts",
                type: "datetime2",
                nullable: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "PhoneVerifiedAt",
                table: "users");

            migrationBuilder.DropColumn(
                name: "CodeCreatedAt",
                table: "otp_attempts");

            migrationBuilder.DropColumn(
                name: "CodeExpiresAt",
                table: "otp_attempts");

            migrationBuilder.DropColumn(
                name: "CodeHash",
                table: "otp_attempts");

            migrationBuilder.DropColumn(
                name: "WindowStartedAt",
                table: "otp_attempts");
        }
    }
}
