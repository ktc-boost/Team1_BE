package knu.team1.be.boost.file.controller;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import knu.team1.be.boost.auth.dto.UserPrincipalDto;
import knu.team1.be.boost.file.dto.FileCompleteRequestDto;
import knu.team1.be.boost.file.dto.FileCompleteResponseDto;
import knu.team1.be.boost.file.dto.FilePresignedUrlResponseDto;
import knu.team1.be.boost.file.dto.FileRequestDto;
import knu.team1.be.boost.file.dto.ProjectFileResponseDto;
import knu.team1.be.boost.file.dto.ProjectFileSummaryResponseDto;
import knu.team1.be.boost.file.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class FileController implements FileApi {

    private final FileService fileService;

    @Override
    public ResponseEntity<FilePresignedUrlResponseDto> uploadFile(
        @Valid @RequestBody FileRequestDto request,
        @AuthenticationPrincipal UserPrincipalDto user
    ) {
        FilePresignedUrlResponseDto response = fileService.uploadFile(request, user);
        URI location = URI.create("/api/files/" + response.fileId());
        return ResponseEntity.created(location).body(response);
    }

    @Override
    public ResponseEntity<FilePresignedUrlResponseDto> downloadFile(
        @PathVariable UUID fileId,
        @AuthenticationPrincipal UserPrincipalDto user
    ) {
        FilePresignedUrlResponseDto response = fileService.downloadFile(fileId, user);
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<FileCompleteResponseDto> completeUpload(
        @PathVariable UUID fileId,
        @Valid @RequestBody FileCompleteRequestDto request,
        @AuthenticationPrincipal UserPrincipalDto user
    ) {
        FileCompleteResponseDto response = fileService.completeUpload(fileId, request, user);
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<List<ProjectFileResponseDto>> getFilesByProject(
        @PathVariable UUID projectId,
        @AuthenticationPrincipal UserPrincipalDto user
    ) {
        List<ProjectFileResponseDto> response = fileService.getFilesByProject(projectId, user.id());
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<ProjectFileSummaryResponseDto> getProjectFileSummary(
        @PathVariable UUID projectId,
        @AuthenticationPrincipal UserPrincipalDto user
    ) {
        ProjectFileSummaryResponseDto response = fileService.getProjectFileSummary(
            projectId,
            user.id()
        );
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<Void> deleteFile(
        @PathVariable UUID fileId,
        @AuthenticationPrincipal UserPrincipalDto user
    ) {
        fileService.deleteFile(fileId, user);
        return ResponseEntity.noContent().build();
    }
}
