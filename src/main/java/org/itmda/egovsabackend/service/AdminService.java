package org.itmda.egovsabackend.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.itmda.egovsabackend.dto.AdminApplicationDto;
import org.itmda.egovsabackend.dto.AdminStatisticsDto;
import org.itmda.egovsabackend.dto.ApplicationDocumentDto;
import org.itmda.egovsabackend.dto.NotificationDto;
import org.itmda.egovsabackend.entity.AdminAction;
import org.itmda.egovsabackend.entity.Application;
import org.itmda.egovsabackend.entity.ApplicationDocument;
import org.itmda.egovsabackend.entity.ApplicationStatusHistory;
import org.itmda.egovsabackend.entity.Profile;
import org.itmda.egovsabackend.repository.AdminActionRepository;
import org.itmda.egovsabackend.repository.ApplicationDocumentRepository;
import org.itmda.egovsabackend.repository.ApplicationRepository;
import org.itmda.egovsabackend.repository.ApplicationStatusHistoryRepository;
import org.itmda.egovsabackend.repository.ProfileRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AdminService {
    
    private final ApplicationRepository applicationRepository;
    private final ProfileRepository profileRepository;
    private final ApplicationDocumentRepository documentRepository;
    private final ApplicationStatusHistoryRepository statusHistoryRepository;
    private final AdminActionRepository adminActionRepository;
    private final NotificationService notificationService;
    
    /**
     * Get all applications with pagination and filtering
     */
    public Page<AdminApplicationDto> getAllApplications(
            int page, 
            int size, 
            String sortBy,
            String status,
            String serviceType,
            String searchTerm) {
        
        Sort sort = Sort.by(Sort.Direction.DESC, sortBy != null ? sortBy : "submittedAt");
        Pageable pageable = PageRequest.of(page, size, sort);
        
        Page<Application> applications;
        
        // Apply filters
        if (status != null && !status.isEmpty() && serviceType != null && !serviceType.isEmpty()) {
            applications = applicationRepository.findAll(pageable);
            applications = applications.map(app -> {
                if (app.getStatus().equalsIgnoreCase(status) && 
                    app.getServiceType().toLowerCase().contains(serviceType.toLowerCase())) {
                    return app;
                }
                return null;
            });
        } else if (status != null && !status.isEmpty()) {
            applications = applicationRepository.findAll(pageable);
            applications = applications.map(app -> 
                app.getStatus().equalsIgnoreCase(status) ? app : null);
        } else if (serviceType != null && !serviceType.isEmpty()) {
            applications = applicationRepository.findByServiceType(serviceType)
                .stream()
                .skip(page * size)
                .limit(size)
                .collect(Collectors.collectingAndThen(
                    Collectors.toList(),
                    list -> new org.springframework.data.domain.PageImpl<>(list, pageable, list.size())
                ));
        } else {
            applications = applicationRepository.findAll(pageable);
        }
        
        return applications.map(this::convertToAdminDto);
    }
    
    /**
     * Get dashboard statistics
     */
    public AdminStatisticsDto getStatistics() {
        long total = applicationRepository.count();
        long underReview = applicationRepository.findByUserIdAndStatus(UUID.randomUUID(), "Under Review").size();
        long completed = applicationRepository.findByUserIdAndStatus(UUID.randomUUID(), "Completed").size();
        long rejected = applicationRepository.findByUserIdAndStatus(UUID.randomUUID(), "Rejected").size();
        long inProgress = applicationRepository.findByUserIdAndStatus(UUID.randomUUID(), "In Progress").size();
        long pendingPayment = applicationRepository.findByUserIdAndStatus(UUID.randomUUID(), "Pending Payment").size();
        
        // More efficient way to count by status
        List<Application> allApps = applicationRepository.findAll();
        underReview = allApps.stream().filter(a -> "Under Review".equals(a.getStatus())).count();
        completed = allApps.stream().filter(a -> "Completed".equals(a.getStatus())).count();
        rejected = allApps.stream().filter(a -> "Rejected".equals(a.getStatus())).count();
        inProgress = allApps.stream().filter(a -> "In Progress".equals(a.getStatus())).count();
        pendingPayment = allApps.stream().filter(a -> "Pending Payment".equals(a.getStatus())).count();
        
        AdminStatisticsDto stats = new AdminStatisticsDto();
        stats.setTotalApplications(total);
        stats.setPendingReview(underReview);
        stats.setApproved(completed);
        stats.setRejected(rejected);
        stats.setInProgress(inProgress);
        stats.setPendingPayment(pendingPayment);
        
        return stats;
    }
    
    /**
     * Approve an application
     */
    @Transactional
    public AdminApplicationDto approveApplication(UUID applicationId, String notes) {
        Application application = applicationRepository.findById(applicationId)
            .orElseThrow(() -> new RuntimeException("Application not found"));
        
        String oldStatus = application.getStatus();
        
        // Update application status
        application.setStatus("Completed");
        application.setCurrentStep("Approved");
        application.setCompletedAt(LocalDateTime.now());
        
        Application updated = applicationRepository.save(application);
        
        // Log status change
        logStatusChange(applicationId, oldStatus, "Completed", notes);
        
        // Log admin action
        logAdminAction("APPROVE", applicationId, application.getUserId(), 
            "Application approved: " + application.getReferenceNumber());
        
        // Send notification to user
        sendApprovalNotification(application);
        
        return convertToAdminDto(updated);
    }
    
    /**
     * Reject an application
     */
    @Transactional
    public AdminApplicationDto rejectApplication(UUID applicationId, String notes) {
        Application application = applicationRepository.findById(applicationId)
            .orElseThrow(() -> new RuntimeException("Application not found"));
        
        String oldStatus = application.getStatus();
        
        // Update application status
        application.setStatus("Rejected");
        application.setCurrentStep("Rejected");
        application.setCompletedAt(LocalDateTime.now());
        
        Application updated = applicationRepository.save(application);
        
        // Log status change
        logStatusChange(applicationId, oldStatus, "Rejected", notes);
        
        // Log admin action
        logAdminAction("REJECT", applicationId, application.getUserId(), 
            "Application rejected: " + application.getReferenceNumber());
        
        // Send notification to user
        sendRejectionNotification(application);
        
        return convertToAdminDto(updated);
    }
    
    /**
     * Update application status (generic)
     */
    @Transactional
    public AdminApplicationDto updateApplicationStatus(UUID applicationId, String status, String currentStep, String notes) {
        Application application = applicationRepository.findById(applicationId)
            .orElseThrow(() -> new RuntimeException("Application not found"));
        
        String oldStatus = application.getStatus();
        
        application.setStatus(status);
        if (currentStep != null) {
            application.setCurrentStep(currentStep);
        }
        
        if ("Completed".equalsIgnoreCase(status) || "Rejected".equalsIgnoreCase(status)) {
            application.setCompletedAt(LocalDateTime.now());
        }
        
        Application updated = applicationRepository.save(application);
        
        // Log status change
        logStatusChange(applicationId, oldStatus, status, notes);
        
        // Log admin action
        logAdminAction("UPDATE_STATUS", applicationId, application.getUserId(), 
            String.format("Status changed from %s to %s", oldStatus, status));
        
        // Send notification
        sendStatusUpdateNotification(application, oldStatus, status);
        
        return convertToAdminDto(updated);
    }
    
    /**
     * Get all users (profiles)
     */
    public List<Profile> getAllUsers() {
        return profileRepository.findAll();
    }
    
    // Helper methods
    
    private void logStatusChange(UUID applicationId, String oldStatus, String newStatus, String notes) {
        ApplicationStatusHistory history = new ApplicationStatusHistory();
        history.setApplicationId(applicationId);
        history.setOldStatus(oldStatus);
        history.setNewStatus(newStatus);
        history.setNotes(notes);
        statusHistoryRepository.save(history);
    }
    
    private void logAdminAction(String actionType, UUID applicationId, UUID userId, String details) {
        AdminAction action = new AdminAction();
        action.setActionType(actionType);
        action.setApplicationId(applicationId);
        action.setUserId(userId);
        action.setDetails(details);
        adminActionRepository.save(action);
    }
    
    private void sendApprovalNotification(Application application) {
        NotificationDto notification = new NotificationDto();
        notification.setUserId(application.getUserId());
        notification.setTitle("Application Approved");
        notification.setDescription(String.format(
            "Your %s application %s has been approved and is now complete.",
            application.getServiceType(),
            application.getReferenceNumber()
        ));
        notification.setNotificationType("application_status");
        notification.setRelatedId(application.getId());
        
        notificationService.createNotification(notification);
    }
    
    private void sendRejectionNotification(Application application) {
        NotificationDto notification = new NotificationDto();
        notification.setUserId(application.getUserId());
        notification.setTitle("Application Rejected");
        notification.setDescription(String.format(
            "Your %s application %s has been rejected. Please contact support for more information.",
            application.getServiceType(),
            application.getReferenceNumber()
        ));
        notification.setNotificationType("application_status");
        notification.setRelatedId(application.getId());
        
        notificationService.createNotification(notification);
    }
    
    private void sendStatusUpdateNotification(Application application, String oldStatus, String newStatus) {
        NotificationDto notification = new NotificationDto();
        notification.setUserId(application.getUserId());
        notification.setTitle("Application Status Updated");
        notification.setDescription(String.format(
            "Your %s application %s status has been updated to: %s",
            application.getServiceType(),
            application.getReferenceNumber(),
            newStatus
        ));
        notification.setNotificationType("application_status");
        notification.setRelatedId(application.getId());
        
        notificationService.createNotification(notification);
    }
    
    private AdminApplicationDto convertToAdminDto(Application application) {
        // Get user profile for applicant details
        Profile profile = profileRepository.findById(application.getUserId()).orElse(null);
        
        // Get documents
        List<ApplicationDocument> docs = documentRepository.findByApplicationId(application.getId());
        List<ApplicationDocumentDto> documentDtos = docs.stream()
            .map(this::convertDocumentToDto)
            .collect(Collectors.toList());
        
        AdminApplicationDto dto = new AdminApplicationDto();
        dto.setId(application.getId());
        dto.setUserId(application.getUserId());
        dto.setApplicantName(profile != null ? profile.getFullName() : "Unknown");
        dto.setApplicantEmail(profile != null ? profile.getEmail() : "");
        dto.setApplicantPhone(profile != null ? profile.getPhone() : "");
        dto.setApplicantIdNumber(profile != null ? profile.getIdNumber() : "");
        dto.setServiceType(application.getServiceType());
        dto.setReferenceNumber(application.getReferenceNumber());
        dto.setStatus(application.getStatus());
        dto.setCurrentStep(application.getCurrentStep());
        dto.setApplicationData(application.getApplicationData());
        dto.setSubmittedAt(application.getSubmittedAt());
        dto.setExpectedCompletionDate(application.getExpectedCompletionDate());
        dto.setCompletedAt(application.getCompletedAt());
        dto.setCreatedAt(application.getCreatedAt());
        dto.setUpdatedAt(application.getUpdatedAt());
        dto.setDocuments(documentDtos);
        
        return dto;
    }
    
    private ApplicationDocumentDto convertDocumentToDto(ApplicationDocument document) {
        ApplicationDocumentDto dto = new ApplicationDocumentDto();
        dto.setId(document.getId());
        dto.setApplicationId(document.getApplicationId());
        dto.setDocumentType(document.getDocumentType());
        dto.setFileName(document.getFileName());
        dto.setFileUrl(document.getFileUrl());
        dto.setFileSize(document.getFileSize());
        dto.setUploadedAt(document.getUploadedAt());
        return dto;
    }
}
