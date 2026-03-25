package knu.team1.be.boost.file.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import knu.team1.be.boost.auth.dto.UserPrincipalDto;
import knu.team1.be.boost.file.dto.FileCompleteRequestDto;
import knu.team1.be.boost.file.dto.FileCompleteResponseDto;
import knu.team1.be.boost.file.dto.FilePresignedUrlResponseDto;
import knu.team1.be.boost.file.dto.FileRequestDto;
import knu.team1.be.boost.file.dto.FileResponseDto;
import knu.team1.be.boost.file.dto.ProjectFileSummaryResponseDto;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@Tag(name = "Files", description = "파일 관련 API")
@RequestMapping("/api")
@SecurityRequirement(name = "bearerAuth")
@Validated
public interface FileApi {

    @Operation(
        summary = "파일 메타 생성 + 업로드 Presigned URL 발급",
        description = "files 테이블에 메타 정보를 생성하고, 업로드 가능한 S3 Presigned URL을 반환합니다."
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "201",
            description = "파일 메타 생성 및 업로드 URL 발급 성공",
            content = @Content(schema = @Schema(implementation = FilePresignedUrlResponseDto.class))
        ),
        @ApiResponse(responseCode = "400", description = "잘못된 요청 데이터", content = @Content),
        @ApiResponse(responseCode = "401", description = "인증 실패", content = @Content),
        @ApiResponse(responseCode = "413", description = "파일 크기 한도 초과", content = @Content),
        @ApiResponse(responseCode = "500", description = "S3 오류", content = @Content),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @PostMapping("/files/upload-url")
    ResponseEntity<FilePresignedUrlResponseDto> uploadFile(
        @Valid @RequestBody FileRequestDto request,
        @AuthenticationPrincipal UserPrincipalDto user
    );

    @Operation(
        summary = "다운로드 Presigned URL 발급",
        description = "파일 ID를 기반으로 다운로드 가능한 S3 Presigned URL을 반환합니다."
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "다운로드 URL 발급 성공",
            content = @Content(schema = @Schema(implementation = FilePresignedUrlResponseDto.class))
        ),
        @ApiResponse(responseCode = "400", description = "잘못된 요청 데이터", content = @Content),
        @ApiResponse(responseCode = "401", description = "인증 실패", content = @Content),
        @ApiResponse(responseCode = "403", description = "권한 없음", content = @Content),
        @ApiResponse(responseCode = "404", description = "파일을 찾을 수 없음", content = @Content),
        @ApiResponse(responseCode = "409", description = "업로드 미완료 상태(다운로드 불가)", content = @Content),
        @ApiResponse(responseCode = "500", description = "S3 오류", content = @Content),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/files/{fileId}/download-url")
    ResponseEntity<FilePresignedUrlResponseDto> downloadFile(
        @PathVariable UUID fileId,
        @AuthenticationPrincipal UserPrincipalDto user
    );

    @Operation(
        summary = "업로드 완료 콜백",
        description = "파일 업로드가 완료되었음을 서버에 알리고 상태를 COMPLETED로 갱신합니다."
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "업로드 완료 처리 성공",
            content = @Content(schema = @Schema(implementation = FileCompleteResponseDto.class))
        ),
        @ApiResponse(responseCode = "400", description = "잘못된 요청 데이터", content = @Content),
        @ApiResponse(responseCode = "401", description = "인증 실패", content = @Content),
        @ApiResponse(responseCode = "403", description = "권한 없음", content = @Content),
        @ApiResponse(responseCode = "404", description = "파일 또는 할 일을 찾을 수 없음", content = @Content),
        @ApiResponse(responseCode = "409", description = "이미 업로드 완료 처리된 파일", content = @Content),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @PatchMapping("/files/{fileId}/complete")
    ResponseEntity<FileCompleteResponseDto> completeUpload(
        @PathVariable UUID fileId,
        @Valid @RequestBody FileCompleteRequestDto request,
        @AuthenticationPrincipal UserPrincipalDto user
    );

    @Operation(
        summary = "프로젝트 파일 목록 조회",
        description = "특정 프로젝트에 속한 파일을 최신순(createdAt DESC)으로 조회합니다. 커서 기반 페이지네이션 없이 전체 리스트를 반환합니다."
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "프로젝트 파일 목록 조회 성공",
            content = @Content(schema = @Schema(implementation = FileResponseDto.class))
        ),
        @ApiResponse(responseCode = "400", description = "잘못된 요청 데이터", content = @Content),
        @ApiResponse(responseCode = "401", description = "인증 실패", content = @Content),
        @ApiResponse(responseCode = "403", description = "권한 없음", content = @Content),
        @ApiResponse(responseCode = "404", description = "프로젝트를 찾을 수 없음", content = @Content),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/projects/{projectId}/files")
    ResponseEntity<List<FileResponseDto>> getFilesByProject(
        @PathVariable UUID projectId,
        @AuthenticationPrincipal UserPrincipalDto user
    );

    @Operation(
        summary = "프로젝트 파일 요약 조회",
        description = """
            특정 프로젝트 내의 전체 파일 개수(totalCount)와 총 용량(totalSizeBytes)을 반환합니다.
            업로드가 완료된(COMPLETED) 파일만 집계됩니다.
            """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "파일 요약 조회 성공",
            content = @Content(schema = @Schema(implementation = ProjectFileSummaryResponseDto.class))
        ),
        @ApiResponse(responseCode = "400", description = "잘못된 요청 데이터", content = @Content),
        @ApiResponse(responseCode = "401", description = "인증 실패", content = @Content),
        @ApiResponse(responseCode = "403", description = "권한 없음", content = @Content),
        @ApiResponse(responseCode = "404", description = "프로젝트를 찾을 수 없음", content = @Content),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/projects/{projectId}/files/summary")
    ResponseEntity<ProjectFileSummaryResponseDto> getProjectFileSummary(
        @PathVariable UUID projectId,
        @AuthenticationPrincipal UserPrincipalDto user
    );

    @Operation(
        summary = "파일 삭제",
        description = "파일 ID를 기반으로 파일을 소프트 삭제합니다. (deleted=true)"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "파일 삭제 성공", content = @Content),
        @ApiResponse(responseCode = "400", description = "잘못된 요청 데이터", content = @Content),
        @ApiResponse(responseCode = "401", description = "인증 실패", content = @Content),
        @ApiResponse(responseCode = "403", description = "권한 없음", content = @Content),
        @ApiResponse(responseCode = "404", description = "파일을 찾을 수 없음", content = @Content),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @DeleteMapping("/files/{fileId}")
    ResponseEntity<Void> deleteFile(
        @PathVariable UUID fileId,
        @AuthenticationPrincipal UserPrincipalDto user
    );
}
