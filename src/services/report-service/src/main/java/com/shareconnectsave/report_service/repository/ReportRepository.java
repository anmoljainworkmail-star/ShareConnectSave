package com.shareconnectsave.report_service.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.shareconnectsave.report_service.model.Report;

/**
 * Repository (via Spring Data): a plain interface, no implementation class
 * written by hand — Spring Data generates both the CRUD implementation
 * (from MongoRepository<Report, String>) AND the query body for
 * findByReportedId purely from the method name, at startup via a JDK
 * dynamic proxy. Same "hide where the data actually lives behind a simple
 * collection-like interface" idea as every other XxxRepository in this
 * project (e.g. IUserRepository on the .NET side, discovery-service's JPA
 * repositories on the Java side) — here the framework writes the concrete
 * class instead of a developer hand-rolling it.
 *
 * Naming: plain ReportRepository, never IReportRepository — Java
 * repositories in this codebase are plain interfaces with no I-prefix,
 * unlike the C# services.
 */
public interface ReportRepository extends MongoRepository<Report, String> {

    List<Report> findByReportedId(String reportedId);
}
