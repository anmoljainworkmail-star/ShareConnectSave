using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace user_service.Migrations
{
    /// <inheritdoc />
    public partial class AddAccountStatusToUsers : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(
                name: "AccountStatus",
                table: "users",
                type: "nvarchar(20)",
                maxLength: 20,
                nullable: false,
                defaultValue: "Active");

            migrationBuilder.AddColumn<DateTime>(
                name: "SuspendedAt",
                table: "users",
                type: "datetime2",
                nullable: true);

            migrationBuilder.CreateIndex(
                name: "IX_users_AccountStatus",
                table: "users",
                column: "AccountStatus");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(
                name: "IX_users_AccountStatus",
                table: "users");

            migrationBuilder.DropColumn(
                name: "AccountStatus",
                table: "users");

            migrationBuilder.DropColumn(
                name: "SuspendedAt",
                table: "users");
        }
    }
}
