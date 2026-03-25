package knu.team1.be.boost.file.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import knu.team1.be.boost.auth.dto.UserPrincipalDto;
import knu.team1.be.boost.common.exception.BusinessException;
import knu.team1.be.boost.common.exception.ErrorCode;
import knu.team1.be.boost.common.policy.AccessPolicy;
import knu.team1.be.boost.file.dto.FileCompleteRequestDto;
import knu.team1.be.boost.file.dto.FileCompleteResponseDto;
import knu.team1.be.boost.file.dto.FilePresignedUrlResponseDto;
import knu.team1.be.boost.file.dto.FileRequestDto;
import knu.team1.be.boost.file.dto.ProjectFileResponseDto;
import knu.team1.be.boost.file.dto.ProjectFileSummaryResponseDto;
import knu.team1.be.boost.file.entity.File;
import knu.team1.be.boost.file.entity.FileType;
import knu.team1.be.boost.file.entity.vo.StorageKey;
import knu.team1.be.boost.file.infra.oci.PresignedUrlFactory;
import knu.team1.be.boost.file.repository.FileRepository;
import knu.team1.be.boost.member.entity.Member;
import knu.team1.be.boost.member.repository.MemberRepository;
import knu.team1.be.boost.project.entity.Project;
import knu.team1.be.boost.project.repository.ProjectRepository;
import knu.team1.be.boost.task.entity.Task;
import knu.team1.be.boost.task.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    private final FileRepository fileRepository;
    private final TaskRepository taskRepository;
    private final MemberRepository memberRepository;
    private final ProjectRepository projectRepository;

    private final AccessPolicy accessPolicy;
    private final PresignedUrlFactory presignedUrlFactory;

    @Value("${boost.oci.bucket}")
    private String bucket;

    @Value("${boost.oci.upload.expire-seconds}")
    private int expireSeconds;

    @Value("${boost.file.max-upload-size}")
    private DataSize maxUploadSize;

    @Transactional
    public FilePresignedUrlResponseDto uploadFile(
        FileRequestDto request,
        UserPrincipalDto user
    ) {
        Member member = memberRepository.findById(user.id())
            .orElseThrow(() -> new BusinessException(
                ErrorCode.MEMBER_NOT_FOUND, "memberId: " + user.id()
            ));

        validateFileLimit(request.sizeBytes(), null);

        FileType fileType = FileType.fromContentType(request.contentType());
        StorageKey key = StorageKey.generate(LocalDateTime.now(), fileType.getExtension());

        File file = File.pendingUpload(request, fileType, key, member);
        File saved = fileRepository.save(file);

        PresignedPutObjectRequest presigned = presignedUrlFactory.forUpload(
            bucket,
            key.value(),
            request.contentType(),
            expireSeconds
        );

        log.info("파일 업로드 presigned URL 발급 성공 - fileId={}, filename={}",
            saved.getId(), saved.getMetadata().originalFilename());
        return FilePresignedUrlResponseDto.forUpload(saved, presigned, expireSeconds);
    }

    @Transactional(readOnly = true)
    public FilePresignedUrlResponseDto downloadFile(
        UUID fileId,
        UserPrincipalDto user
    ) {
        File file = fileRepository.findById(fileId)
            .orElseThrow(() -> new BusinessException(
                ErrorCode.FILE_NOT_FOUND, "fileId: " + fileId
            ));

        if (!file.isComplete()) {
            throw new BusinessException(
                ErrorCode.FILE_NOT_READY, "fileId: " + fileId
            );
        }

        UUID projectId = file.getTask().getProject().getId();
        accessPolicy.ensureProjectMember(projectId, user.id());

        PresignedGetObjectRequest presigned = presignedUrlFactory.forDownload(
            bucket,
            file.getStorageKey().value(),
            file.getMetadata().originalFilename(),
            file.getMetadata().contentType(),
            expireSeconds
        );

        log.info("파일 다운로드 presigned URL 발급 성공 - fileId={}, filename={}",
            file.getId(), file.getMetadata().originalFilename());
        return FilePresignedUrlResponseDto.forDownload(file, presigned, expireSeconds);
    }

    @Transactional
    public FileCompleteResponseDto completeUpload(
        UUID fileId,
        FileCompleteRequestDto request,
        UserPrincipalDto user
    ) {
        File file = fileRepository.findById(fileId)
            .orElseThrow(() -> new BusinessException(
                ErrorCode.FILE_NOT_FOUND, "fileId: " + fileId
            ));

        if (file.isComplete()) {
            throw new BusinessException(
                ErrorCode.FILE_ALREADY_UPLOAD_COMPLETED, "fileId: " + fileId
            );
        }

        validateFileLimit(request.sizeBytes(), fileId);

        file.getMetadata()
            .validateMatches(request.filename(), request.contentType(), request.sizeBytes());

        UUID taskId = request.taskId();
        Task task = taskRepository.findById(taskId)
            .orElseThrow(() -> new BusinessException(
                ErrorCode.TASK_NOT_FOUND,
                "파일 업로드 완료 실패 " + "taskId: " + taskId + ", fileId: " + fileId
            ));

        UUID projectId = task.getProject().getId();
        accessPolicy.ensureProjectMember(projectId, user.id());
        accessPolicy.ensureTaskAssignee(taskId, user.id());

        file.assignTask(task);
        file.complete();

        log.info("파일 업로드 완료 처리 성공 - fileId={}, taskId={}, filename={}",
            fileId, taskId, request.filename());
        return FileCompleteResponseDto.from(file, taskId);
    }

    @Transactional(readOnly = true)
    public List<ProjectFileResponseDto> getFilesByProject(UUID projectId, UUID userId) {
        Project project = projectRepository.findById(projectId)
            .orElseThrow(() -> new BusinessException(
                ErrorCode.PROJECT_NOT_FOUND,
                "projectId: " + projectId
            ));

        accessPolicy.ensureProjectMember(project.getId(), userId);

        List<File> files = fileRepository.findAllByProjectId(project.getId());

        return files.stream()
            .map(ProjectFileResponseDto::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public ProjectFileSummaryResponseDto getProjectFileSummary(
        UUID projectId,
        UUID userId
    ) {
        Project project = projectRepository.findById(projectId)
            .orElseThrow(() -> new BusinessException(
                ErrorCode.PROJECT_NOT_FOUND, "projectId: " + projectId
            ));

        accessPolicy.ensureProjectMember(project.getId(), userId);

        long totalCount = fileRepository.countByProject(projectId);
        long totalSize = fileRepository.sumSizeByProject(projectId);

        return ProjectFileSummaryResponseDto.from(totalCount, totalSize);
    }

    @Transactional
    public void deleteFile(UUID fileId, UserPrincipalDto user) {
        File file = fileRepository.findById(fileId)
            .orElseThrow(() -> new BusinessException(
                ErrorCode.FILE_NOT_FOUND, "fileId: " + fileId
            ));

        Task task = file.getTask();
        if (task == null) {
            throw new BusinessException(
                ErrorCode.TASK_NOT_FOUND,
                "파일에 연결된 할 일이 존재하지 않습니다. fileId: " + fileId
            );
        }

        Project project = task.getProject();
        if (project == null) {
            throw new BusinessException(
                ErrorCode.PROJECT_NOT_FOUND,
                "작업에 연결된 프로젝트가 존재하지 않습니다. fileId: " + fileId + ", taskId: " + task.getId()
            );
        }

        accessPolicy.ensureProjectMember(project.getId(), user.id());
        accessPolicy.ensureTaskAssignee(task.getId(), user.id());

        fileRepository.delete(file);
    }

    private void validateFileLimit(long sizeBytes, UUID fileIdOrNull) {
        long max = maxUploadSize.toBytes();
        if (sizeBytes > max) {
            String msg = "size: " + sizeBytes + ", max: " + max;
            if (fileIdOrNull != null) {
                msg = "fileId: " + fileIdOrNull + ", " + msg;
            }
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, msg);
        }
    }
}
