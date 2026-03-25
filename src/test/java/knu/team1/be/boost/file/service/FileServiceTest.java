package knu.team1.be.boost.file.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.list;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URL;
import java.util.List;
import java.util.Optional;
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
import knu.team1.be.boost.file.entity.FileStatus;
import knu.team1.be.boost.file.entity.FileType;
import knu.team1.be.boost.file.entity.vo.FileMetadata;
import knu.team1.be.boost.file.entity.vo.StorageKey;
import knu.team1.be.boost.file.infra.oci.PresignedUrlFactory;
import knu.team1.be.boost.file.repository.FileRepository;
import knu.team1.be.boost.member.entity.Member;
import knu.team1.be.boost.member.repository.MemberRepository;
import knu.team1.be.boost.project.entity.Project;
import knu.team1.be.boost.project.repository.ProjectRepository;
import knu.team1.be.boost.task.entity.Task;
import knu.team1.be.boost.task.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

@ExtendWith(MockitoExtension.class)
class FileServiceTest {

    @Mock
    FileRepository fileRepository;
    @Mock
    TaskRepository taskRepository;
    @Mock
    MemberRepository memberRepository;
    @Mock
    ProjectRepository projectRepository;
    @Mock
    AccessPolicy accessPolicy;
    @Mock
    PresignedUrlFactory presignedUrlFactory;

    FileService fileService;

    UUID userId;
    UUID projectId;
    UserPrincipalDto user;
    Member member;
    Project project;
    Task task;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        user = UserPrincipalDto.from(userId, "test-user", "avatar-code");
        member = Fixtures.member(userId);
        project = Fixtures.project(projectId);
        task = Fixtures.task(UUID.randomUUID(), project);

        fileService = new FileService(
            fileRepository,
            taskRepository,
            memberRepository,
            projectRepository,
            accessPolicy,
            presignedUrlFactory
        );
        ReflectionTestUtils.setField(fileService, "bucket", Fixtures.BUCKET);
        ReflectionTestUtils.setField(fileService, "expireSeconds", Fixtures.EXPIRES);
        ReflectionTestUtils.setField(fileService, "maxUploadSize", DataSize.ofMegabytes(5));
    }

    @Nested
    @DisplayName("업로드 Presigned URL 발급")
    class UploadPresign {

        @Test
        @DisplayName("업로드 Presigned URL 발급 성공")
        void test1() throws Exception {
            // given
            FileRequestDto req = Fixtures.reqUpload();
            given(memberRepository.findById(userId)).willReturn(Optional.of(member));
            given(fileRepository.save(any(File.class))).willAnswer(inv -> inv.getArgument(0));

            PresignedPutObjectRequest putReq = mock(PresignedPutObjectRequest.class);
            when(putReq.url()).thenReturn(new URL(Fixtures.PRESIGNED_URL));

            given(presignedUrlFactory.forUpload(anyString(), anyString(), anyString(), anyInt()))
                .willReturn(putReq);

            // when
            FilePresignedUrlResponseDto res = fileService.uploadFile(req, user);

            // then
            assertThat(res.method()).isEqualTo("PUT");
            assertThat(res.url()).isEqualTo(Fixtures.PRESIGNED_URL);
            assertThat(res.expiresInSeconds()).isEqualTo(Fixtures.EXPIRES);

            ArgumentCaptor<File> cap = ArgumentCaptor.forClass(File.class);
            verify(fileRepository).save(cap.capture());
            File saved = cap.getValue();
            assertThat(saved.getType()).isEqualTo(FileType.PDF);
            assertThat(saved.getStatus()).isEqualTo(FileStatus.PENDING);
            assertThat(saved.getMetadata().originalFilename()).isEqualTo(Fixtures.FILENAME);
        }

        @Test
        @DisplayName("업로드 Presigned URL 발급 실패 - 파일 크기 초과(413)")
        void test2() {
            // given
            FileRequestDto req = new FileRequestDto(Fixtures.FILENAME, Fixtures.CT_PDF,
                500_000_000);
            given(memberRepository.findById(userId)).willReturn(Optional.of(member));

            // when & then
            assertThatThrownBy(() -> fileService.uploadFile(req, user))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FILE_TOO_LARGE);

            verifyNoInteractions(fileRepository, presignedUrlFactory);
        }
    }

    @Nested
    @DisplayName("다운로드 Presigned URL 발급")
    class DownloadPresign {

        @Test
        @DisplayName("다운로드 Pre-Signed URL 발급 성공")
        void test1() throws Exception {
            // given
            UUID fileId = UUID.randomUUID();
            File file = Fixtures.fileCompleted(fileId, member, task);
            given(fileRepository.findById(fileId)).willReturn(Optional.of(file));
            doNothing().when(accessPolicy).ensureProjectMember(eq(projectId), eq(userId));

            PresignedGetObjectRequest getReq = mock(PresignedGetObjectRequest.class);
            when(getReq.url()).thenReturn(new URL(Fixtures.PRESIGNED_URL));

            given(
                presignedUrlFactory.forDownload(anyString(), anyString(), anyString(), anyString(),
                    anyInt()))
                .willReturn(getReq);

            // when
            FilePresignedUrlResponseDto res = fileService.downloadFile(fileId, user);

            // then
            assertThat(res.method()).isEqualTo("GET");
            assertThat(res.url()).isEqualTo(Fixtures.PRESIGNED_URL);
            assertThat(res.key()).isEqualTo(file.getStorageKey().value());
        }

        @Test
        @DisplayName("다운로드 Pre-Signed URL 발급 실패 - 404(파일 없음)")
        void test2() {
            // given
            UUID fileId = UUID.randomUUID();
            given(fileRepository.findById(fileId)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> fileService.downloadFile(fileId, user))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FILE_NOT_FOUND);

            verifyNoInteractions(presignedUrlFactory);
        }

        @Test
        @DisplayName("다운로드 Pre-Signed URL 발급 실패 - 409(업로드 미완료)")
        void test3() {
            // given
            UUID fileId = UUID.randomUUID();
            File file = Fixtures.filePending(fileId, member, task);
            given(fileRepository.findById(fileId)).willReturn(Optional.of(file));

            // when & then
            assertThatThrownBy(() -> fileService.downloadFile(fileId, user))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FILE_NOT_READY);

            verifyNoInteractions(presignedUrlFactory);
        }
    }

    @Nested
    @DisplayName("업로드 완료")
    class CompleteUpload {

        @Test
        @DisplayName("업로드 완료 성공")
        void test1() {
            // given
            UUID fileId = UUID.randomUUID();
            UUID taskId = task.getId();
            File file = Fixtures.filePending(fileId, member, null);
            given(fileRepository.findById(fileId)).willReturn(Optional.of(file));
            given(taskRepository.findById(taskId)).willReturn(Optional.of(task));
            doNothing().when(accessPolicy).ensureProjectMember(eq(projectId), eq(userId));
            doNothing().when(accessPolicy).ensureTaskAssignee(eq(taskId), eq(userId));

            FileCompleteRequestDto req = Fixtures.reqComplete(taskId);

            // when
            FileCompleteResponseDto res = fileService.completeUpload(fileId, req, user);

            // then
            assertThat(res.fileId()).isEqualTo(fileId);
            assertThat(file.getStatus()).isEqualTo(FileStatus.COMPLETED);
            assertThat(file.getTask()).isEqualTo(task);
        }

        @Test
        @DisplayName("업로드 완료 실패 - 400(메타데이터 불일치)")
        void test2() {
            // given
            UUID fileId = UUID.randomUUID();
            File file = Fixtures.filePending(fileId, member, null);
            given(fileRepository.findById(fileId)).willReturn(Optional.of(file));

            FileCompleteRequestDto wrong = new FileCompleteRequestDto(
                UUID.randomUUID(), "다른 파일.pdf", Fixtures.CT_PDF, 1234
            );

            // when & then
            assertThatThrownBy(() -> fileService.completeUpload(fileId, wrong, user))
                .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(taskRepository, accessPolicy);
        }

        @Test
        @DisplayName("업로드 완료 실패 - 404(파일 없음)")
        void test3() {
            // given
            UUID fileId = UUID.randomUUID();
            FileCompleteRequestDto req = Fixtures.reqComplete(UUID.randomUUID());
            given(fileRepository.findById(fileId)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> fileService.completeUpload(fileId, req, user))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FILE_NOT_FOUND);

            verifyNoInteractions(taskRepository, accessPolicy);
        }

        @Test
        @DisplayName("업로드 완료 실패 - 404(할 일 없음)")
        void test4() {
            // given
            UUID fileId = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            File file = Fixtures.filePending(fileId, member, null);
            given(fileRepository.findById(fileId)).willReturn(Optional.of(file));
            given(taskRepository.findById(taskId)).willReturn(Optional.empty());

            FileCompleteRequestDto req = Fixtures.reqComplete(taskId);

            // when & then
            assertThatThrownBy(() -> fileService.completeUpload(fileId, req, user))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TASK_NOT_FOUND);

            verifyNoInteractions(accessPolicy);
        }

        @Test
        @DisplayName("업로드 완료 실패 - 409(이미 완료된 파일)")
        void test5() {
            // given
            UUID fileId = UUID.randomUUID();
            File file = Fixtures.fileCompleted(fileId, member, null);
            given(fileRepository.findById(fileId)).willReturn(Optional.of(file));

            FileCompleteRequestDto req = Fixtures.reqComplete(UUID.randomUUID());

            // when & then
            assertThatThrownBy(() -> fileService.completeUpload(fileId, req, user))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FILE_ALREADY_UPLOAD_COMPLETED);

            verifyNoInteractions(taskRepository, accessPolicy);
        }
    }

    @Nested
    @DisplayName("프로젝트 파일 목록 조회")
    class GetFilesByProject {

        @Test
        @DisplayName("프로젝트 파일 목록 조회 성공")
        void success() {
            // given
            given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
            doNothing().when(accessPolicy).ensureProjectMember(eq(projectId), eq(userId));

            File f1 = Fixtures.fileCompleted(UUID.randomUUID(), member, task);
            File f2 = Fixtures.fileCompleted(UUID.randomUUID(), member, task);
            List<File> files = List.of(f1, f2);

            given(fileRepository.findAllByProjectId(projectId))
                .willReturn(files);

            // when
            List<ProjectFileResponseDto> res = fileService.getFilesByProject(projectId, userId);

            // then
            assertThat(res)
                .asInstanceOf(list(ProjectFileResponseDto.class))
                .hasSize(2);
            assertThat(res.get(0).fileId()).isEqualTo(f1.getId());
            assertThat(res.get(1).taskId()).isEqualTo(f2.getTask().getId());

            verify(accessPolicy).ensureProjectMember(projectId, userId);
        }

        @Test
        @DisplayName("프로젝트 파일 목록 조회 실패 - 404(프로젝트 없음)")
        void fail_projectNotFound() {
            // given
            given(projectRepository.findById(projectId)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() ->
                fileService.getFilesByProject(projectId, userId)
            )
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_FOUND);

            verifyNoInteractions(accessPolicy);
        }
    }

    @Nested
    @DisplayName("프로젝트 파일 요약 조회")
    class ProjectFileSummary {

        @Test
        @DisplayName("프로젝트 파일 요약 조회 성공")
        void success() {
            // given
            given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
            doNothing().when(accessPolicy).ensureProjectMember(eq(projectId), eq(userId));
            given(fileRepository.countByProject(projectId)).willReturn(5L);
            given(fileRepository.sumSizeByProject(projectId)).willReturn(10_485_760L);

            // when
            ProjectFileSummaryResponseDto res =
                fileService.getProjectFileSummary(projectId, userId);

            // then
            assertThat(res.totalCount()).isEqualTo(5);
            assertThat(res.totalSizeBytes()).isEqualTo(10_485_760L);
            verify(accessPolicy).ensureProjectMember(projectId, userId);
        }

        @Test
        @DisplayName("프로젝트 파일 요약 조회 실패 - 404(프로젝트 없음)")
        void fail_projectNotFound() {
            // given
            given(projectRepository.findById(projectId)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() ->
                fileService.getProjectFileSummary(projectId, userId)
            )
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_FOUND);

            verifyNoInteractions(accessPolicy, fileRepository);
        }
    }

    @Nested
    @DisplayName("파일 삭제")
    class DeleteFile {

        @Test
        @DisplayName("파일 삭제 성공")
        void success() {
            // given
            UUID fileId = UUID.randomUUID();
            File file = Fixtures.fileCompleted(fileId, member, task);
            given(fileRepository.findById(fileId)).willReturn(Optional.of(file));
            doNothing().when(accessPolicy).ensureProjectMember(eq(projectId), eq(userId));
            doNothing().when(accessPolicy).ensureTaskAssignee(eq(task.getId()), eq(userId));

            // when
            fileService.deleteFile(fileId, user);

            // then
            verify(fileRepository).delete(file);
        }

        @Test
        @DisplayName("파일 삭제 실패 - 404(파일 없음)")
        void fail_notFound() {
            // given
            UUID fileId = UUID.randomUUID();
            given(fileRepository.findById(fileId)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> fileService.deleteFile(fileId, user))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FILE_NOT_FOUND);

            verifyNoInteractions(accessPolicy);
        }

        @Test
        @DisplayName("파일 삭제 실패 - 404(연결된 Task 없음)")
        void fail_noTask() {
            // given
            UUID fileId = UUID.randomUUID();
            File file = Fixtures.fileCompleted(fileId, member, null);
            given(fileRepository.findById(fileId)).willReturn(Optional.of(file));

            // when & then
            assertThatThrownBy(() -> fileService.deleteFile(fileId, user))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TASK_NOT_FOUND);

            verifyNoInteractions(accessPolicy);
        }

        @Test
        @DisplayName("파일 삭제 실패 - 404(연결된 프로젝트 없음)")
        void fail_noProject() {
            // given
            UUID fileId = UUID.randomUUID();
            Task orphanTask = Task.builder().id(UUID.randomUUID()).project(null).build();
            File file = Fixtures.fileCompleted(fileId, member, orphanTask);
            given(fileRepository.findById(fileId)).willReturn(Optional.of(file));

            // when & then
            assertThatThrownBy(() -> fileService.deleteFile(fileId, user))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_FOUND);

            verifyNoInteractions(accessPolicy);
        }
    }


    static class Fixtures {

        static final String BUCKET = "test-bucket";
        static final int EXPIRES = 900;
        static final String PRESIGNED_URL = "https://boost-s3-bucket-storage.s3.ap-northeast-2.amazonaws.com/...";
        static final String FILENAME = "최종 보고서.pdf";
        static final String CT_PDF = "application/pdf";
        static final int SIZE_1MB = 1_048_576;

        static Member member(UUID id) {
            return Member.builder().id(id).build();
        }

        static Project project(UUID id) {
            return Project.builder().id(id).build();
        }

        static Task task(UUID id, Project project) {
            return Task.builder().id(id).project(project).build();
        }

        static File filePending(UUID id, Member member, Task task) {
            return baseFile(id, member, task, FileStatus.PENDING);
        }

        static File fileCompleted(UUID id, Member member, Task task) {
            return baseFile(id, member, task, FileStatus.COMPLETED);
        }

        private static File baseFile(UUID id, Member member, Task task, FileStatus status) {
            String key = "file/2025/09/10/" + id + ".pdf";
            return File.builder()
                .id(id)
                .member(member)
                .task(task)
                .metadata(FileMetadata.of(FILENAME, CT_PDF, SIZE_1MB))
                .type(FileType.fromContentType(CT_PDF))
                .storageKey(new StorageKey(key))
                .status(status)
                .build();
        }

        static FileRequestDto reqUpload() {
            return new FileRequestDto(FILENAME, CT_PDF, SIZE_1MB);
        }

        static FileCompleteRequestDto reqComplete(UUID taskId) {
            return new FileCompleteRequestDto(taskId, FILENAME, CT_PDF, SIZE_1MB);
        }
    }
}
