package knu.team1.be.boost.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import knu.team1.be.boost.auth.dto.UserPrincipalDto;
import knu.team1.be.boost.comment.repository.CommentRepository;
import knu.team1.be.boost.common.exception.BusinessException;
import knu.team1.be.boost.common.exception.ErrorCode;
import knu.team1.be.boost.common.policy.AccessPolicy;
import knu.team1.be.boost.file.repository.FileRepository;
import knu.team1.be.boost.member.entity.Member;
import knu.team1.be.boost.member.repository.MemberRepository;
import knu.team1.be.boost.project.entity.Project;
import knu.team1.be.boost.project.repository.ProjectRepository;
import knu.team1.be.boost.projectMembership.entity.ProjectMembership;
import knu.team1.be.boost.projectMembership.repository.ProjectMembershipRepository;
import knu.team1.be.boost.tag.entity.Tag;
import knu.team1.be.boost.tag.repository.TagRepository;
import knu.team1.be.boost.task.dto.MemberTaskStatusCount;
import knu.team1.be.boost.task.dto.ProjectTaskStatusCount;
import knu.team1.be.boost.task.dto.TaskCreateRequestDto;
import knu.team1.be.boost.task.dto.TaskResponseDto;
import knu.team1.be.boost.task.dto.TaskSortBy;
import knu.team1.be.boost.task.dto.TaskSortDirection;
import knu.team1.be.boost.task.dto.TaskStatusRequestDto;
import knu.team1.be.boost.task.dto.TaskUpdateRequestDto;
import knu.team1.be.boost.task.entity.Task;
import knu.team1.be.boost.task.entity.TaskStatus;
import knu.team1.be.boost.task.event.TaskEventPublisher;
import knu.team1.be.boost.task.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    TagRepository tagRepository;
    @Mock
    TaskRepository taskRepository;
    @Mock
    FileRepository fileRepository;
    @Mock
    MemberRepository memberRepository;
    @Mock
    CommentRepository commentRepository;
    @Mock
    ProjectRepository projectRepository;
    @Mock
    ProjectMembershipRepository projectMembershipRepository;
    @Mock
    TaskEventPublisher taskEventPublisher;
    @Mock
    AccessPolicy accessPolicy;

    TaskService taskService;

    UUID userId;
    UserPrincipalDto user;
    UUID projectId;
    Project project;
    Task baseTask;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        user = UserPrincipalDto.from(userId, "test-user", "avatar-code");
        projectId = UUID.randomUUID();
        project = Fixtures.project(projectId);
        baseTask = Fixtures.task(UUID.randomUUID(), project);

        taskService = new TaskService(
            tagRepository,
            taskRepository,
            fileRepository,
            memberRepository,
            commentRepository,
            projectRepository,
            projectMembershipRepository,
            accessPolicy,
            taskEventPublisher
        );
    }

    @Nested
    @DisplayName("할 일 생성")
    class CreateTask {

        @Test
        @DisplayName("할 일 생성 성공")
        void test1() {
            // given
            given(projectRepository.findById(projectId)).willReturn(Optional.of(project));

            UUID m1 = UUID.randomUUID();
            UUID m2 = UUID.randomUUID();
            given(memberRepository.findAllById(anyList()))
                .willReturn(List.of(Fixtures.member(m1, "영진"), Fixtures.member(m2, "비버")));

            doNothing().when(accessPolicy)
                .ensureProjectMember(eq(projectId), eq(userId));
            doNothing().when(accessPolicy)
                .ensureAssigneesAreProjectMembers(eq(projectId), any(Set.class));

            UUID t1 = UUID.randomUUID();
            UUID t2 = UUID.randomUUID();
            given(tagRepository.findAllById(anyList()))
                .willReturn(
                    List.of(Fixtures.tag(t1, "태그1", project), Fixtures.tag(t2, "태그2", project)));

            given(projectMembershipRepository.countByProjectId(projectId)).willReturn(4);

            TaskCreateRequestDto request = Fixtures.reqCreate(
                "1회차 기술 멘토링 피드백 반영",
                "기술 멘토링에서 나온 멘토님의 피드백을 반영한다.",
                TaskStatus.TODO,
                Fixtures.DUE,
                false,
                1,
                List.of(t1, t2),
                List.of(m1, m2)
            );

            given(taskRepository.save(any(Task.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

            // when
            TaskResponseDto res = taskService.createTask(projectId, request, user);

            // then
            assertThat(res.projectId()).isEqualTo(projectId);
            assertThat(res.title()).isEqualTo("1회차 기술 멘토링 피드백 반영");
            assertThat(res.description()).isEqualTo("기술 멘토링에서 나온 멘토님의 피드백을 반영한다.");
            assertThat(res.status()).isEqualTo(TaskStatus.TODO);
            assertThat(res.dueDate()).isEqualTo(Fixtures.DUE);
            assertThat(res.urgent()).isFalse();
            assertThat(res.requiredReviewerCount()).isEqualTo(1);
            assertThat(res.tags()).hasSize(2);
            assertThat(res.assignees()).hasSize(2);

            ArgumentCaptor<Task> cap = ArgumentCaptor.forClass(Task.class);
            verify(taskRepository).save(cap.capture());
            Task saved = cap.getValue();
            assertThat(saved.getProject()).isEqualTo(project);
            assertThat(saved.getAssignees()).extracting("id")
                .containsExactlyInAnyOrder(m1, m2);
        }

        @Test
        @DisplayName("할 일 생성 실패 - 404 (프로젝트 없음)")
        void test2() {
            // given
            given(projectRepository.findById(projectId)).willReturn(Optional.empty());

            TaskCreateRequestDto request = Fixtures.reqCreate(
                "1회차 기술 멘토링 피드백 반영",
                "기술 멘토링에서 나온 멘토님의 피드백을 반영한다.",
                TaskStatus.TODO,
                Fixtures.DUE,
                false,
                1,
                List.of(),
                List.of()
            );

            // when & then
            assertThatThrownBy(() -> taskService.createTask(projectId, request, user))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_FOUND);

            verifyNoInteractions(taskRepository, memberRepository, accessPolicy);
        }

        @Test
        @DisplayName("할 일 생성 실패 - 404 (담당자 일부 없음)")
        void test3() {
            // given
            given(projectRepository.findById(projectId)).willReturn(Optional.of(project));

            UUID m1 = UUID.randomUUID();
            UUID missing = UUID.randomUUID();
            given(memberRepository.findAllById(anyList()))
                .willReturn(List.of(Fixtures.member(m1, "비버")));

            TaskCreateRequestDto request = Fixtures.reqCreate(
                "1회차 기술 멘토링 피드백 반영",
                "기술 멘토링에서 나온 멘토님의 피드백을 반영한다.",
                TaskStatus.TODO,
                Fixtures.DUE,
                false,
                1,
                List.of(),
                List.of(m1, missing)
            );

            // when & then
            assertThatThrownBy(() -> taskService.createTask(projectId, request, user))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEMBER_NOT_FOUND);

            verify(taskRepository, never()).save(any());
            verify(accessPolicy)
                .ensureProjectMember(eq(projectId), eq(userId));
            verify(accessPolicy, never())
                .ensureAssigneesAreProjectMembers(eq(projectId), any(Set.class));
        }

        @Test
        @DisplayName("할 일 생성 실패 - 태그가 다른 프로젝트에 속함")
        void test4() {
            // given
            given(projectRepository.findById(projectId)).willReturn(Optional.of(project));

            UUID tagId = UUID.randomUUID();
            Project otherProject = Fixtures.project(UUID.randomUUID());
            Tag tag = Fixtures.tag(tagId, "다른프로젝트태그", otherProject);

            given(tagRepository.findAllById(anyList())).willReturn(List.of(tag));
            doNothing().when(accessPolicy).ensureProjectMember(eq(projectId), eq(userId));

            TaskCreateRequestDto request = Fixtures.reqCreate(
                "태그 오류 테스트",
                "태그가 다른 프로젝트에 속해 있을 때 예외",
                TaskStatus.TODO,
                Fixtures.DUE,
                false,
                1,
                List.of(tagId),
                List.of()
            );

            // when & then
            assertThatThrownBy(() -> taskService.createTask(projectId, request, user))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TAG_NOT_IN_PROJECT);

            verify(taskRepository, never()).save(any());
        }

        @Test
        @DisplayName("할 일 생성 성공 - 필수 리뷰어 수가 가능한 리뷰어 수와 같음")
        void test5() {
            // given
            given(projectRepository.findById(projectId)).willReturn(Optional.of(project));

            UUID m1 = UUID.randomUUID();
            UUID m2 = UUID.randomUUID();
            given(memberRepository.findAllById(anyList()))
                .willReturn(List.of(Fixtures.member(m1, "영진"), Fixtures.member(m2, "비버")));

            doNothing().when(accessPolicy)
                .ensureProjectMember(eq(projectId), eq(userId));
            doNothing().when(accessPolicy)
                .ensureAssigneesAreProjectMembers(eq(projectId), any(Set.class));

            given(projectMembershipRepository.countByProjectId(projectId)).willReturn(4);

            TaskCreateRequestDto request = Fixtures.reqCreate(
                "리뷰어 수 경계 테스트",
                "필수 리뷰어 수가 가능한 리뷰어 수와 같을 때 성공",
                TaskStatus.TODO,
                Fixtures.DUE,
                false,
                2,
                List.of(),
                List.of(m1, m2)
            );

            given(taskRepository.save(any(Task.class))).willAnswer(
                invocation -> invocation.getArgument(0));

            // when
            TaskResponseDto res = taskService.createTask(projectId, request, user);

            // then
            assertThat(res.requiredReviewerCount()).isEqualTo(2);
            verify(taskRepository).save(any(Task.class));
        }

        @Test
        @DisplayName("할 일 생성 실패 - 필수 리뷰어 수가 가능한 리뷰어 수보다 많음")
        void test6() {
            // given
            given(projectRepository.findById(projectId)).willReturn(Optional.of(project));

            UUID m1 = UUID.randomUUID();
            UUID m2 = UUID.randomUUID();
            given(memberRepository.findAllById(anyList()))
                .willReturn(List.of(Fixtures.member(m1, "영진"), Fixtures.member(m2, "비버")));

            doNothing().when(accessPolicy)
                .ensureProjectMember(eq(projectId), eq(userId));
            doNothing().when(accessPolicy)
                .ensureAssigneesAreProjectMembers(eq(projectId), any(Set.class));

            given(projectMembershipRepository.countByProjectId(projectId)).willReturn(3);

            TaskCreateRequestDto request = Fixtures.reqCreate(
                "리뷰어 수 초과 테스트",
                "필수 리뷰어 수가 가능한 리뷰어 수보다 많으면 예외 발생",
                TaskStatus.TODO,
                Fixtures.DUE,
                false,
                2,
                List.of(),
                List.of(m1, m2)
            );

            // when & then
            assertThatThrownBy(() -> taskService.createTask(projectId, request, user))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode",
                    ErrorCode.INVALID_REQUIRED_REVIEWER_COUNT);

            verify(taskRepository, never()).save(any());
        }

    }

    @Nested
    @DisplayName("할 일 수정")
    class UpdateTask {

        @Test
        @DisplayName("할 일 수정 성공")
        void test1() {
            // given
            UUID taskId = baseTask.getId();

            given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
            given(taskRepository.findById(taskId)).willReturn(Optional.of(baseTask));

            UUID m1 = UUID.randomUUID();
            given(memberRepository.findAllById(anyList()))
                .willReturn(List.of(Fixtures.member(m1, "비버")));

            UUID t1 = UUID.randomUUID();
            given(tagRepository.findAllById(anyList()))
                .willReturn(List.of(Fixtures.tag(t1, "태그1", project)));

            given(projectMembershipRepository.countByProjectId(projectId)).willReturn(4);

            doNothing().when(accessPolicy)
                .ensureProjectMember(eq(projectId), eq(userId));
            doNothing().when(accessPolicy)
                .ensureAssigneesAreProjectMembers(eq(projectId), any(Set.class));

            TaskUpdateRequestDto request = Fixtures.reqUpdate(
                "수정된 제목",
                "수정된 설명",
                TaskStatus.PROGRESS,
                Fixtures.DUE2,
                true,
                3,
                List.of(t1),
                List.of(m1)
            );

            // when
            TaskResponseDto res = taskService.updateTask(projectId, taskId, request, user);

            // then
            assertThat(res.title()).isEqualTo("수정된 제목");
            assertThat(res.description()).isEqualTo("수정된 설명");
            assertThat(res.status()).isEqualTo(TaskStatus.PROGRESS);
            assertThat(res.dueDate()).isEqualTo(Fixtures.DUE2);
            assertThat(res.urgent()).isTrue();
            assertThat(res.requiredReviewerCount()).isEqualTo(3);
            assertThat(res.tags()).hasSize(1);
            assertThat(res.assignees()).hasSize(1);
        }

        @Test
        @DisplayName("할 일 수정 실패 - 404 (프로젝트 없음)")
        void test2() {
            // given
            UUID taskId = UUID.randomUUID();
            given(projectRepository.findById(projectId)).willReturn(Optional.empty());

            TaskUpdateRequestDto request = Fixtures.reqUpdate(
                "수정된 제목",
                "수정된 설명",
                TaskStatus.PROGRESS,
                Fixtures.DUE2,
                true,
                3,
                List.of(),
                List.of()
            );

            // when & then
            assertThatThrownBy(() -> taskService.updateTask(projectId, taskId, request, user))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_FOUND);

            verifyNoInteractions(taskRepository, memberRepository, accessPolicy);
        }

        @Test
        @DisplayName("할 일 수정 실패 - 404 (할 일 없음)")
        void test3() {
            // given
            UUID taskId = UUID.randomUUID();
            given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
            given(taskRepository.findById(taskId)).willReturn(Optional.empty());

            TaskUpdateRequestDto request = Fixtures.reqUpdate(
                "수정된 제목",
                "수정된 설명",
                TaskStatus.PROGRESS,
                Fixtures.DUE2,
                true,
                3,
                List.of(),
                List.of()
            );

            // when & then
            assertThatThrownBy(() -> taskService.updateTask(projectId, taskId, request, user))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TASK_NOT_FOUND);

            verifyNoInteractions(memberRepository, accessPolicy);
        }

        @Test
        @DisplayName("할 일 수정 실패 - 409 (다른 프로젝트의 할 일)")
        void test4() {
            // given
            UUID otherProjectId = UUID.randomUUID();
            Project otherProject = Fixtures.project(otherProjectId);
            given(projectRepository.findById(projectId)).willReturn(Optional.of(project));

            Task existing = Fixtures.task(UUID.randomUUID(), otherProject);
            given(taskRepository.findById(existing.getId())).willReturn(Optional.of(existing));

            TaskUpdateRequestDto request = Fixtures.reqUpdate(
                "수정된 제목",
                "수정된 설명",
                TaskStatus.PROGRESS,
                Fixtures.DUE2,
                true,
                3,
                List.of(),
                List.of()
            );

            // when & then
            assertThatThrownBy(
                () -> taskService.updateTask(projectId, existing.getId(), request, user))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TASK_NOT_IN_PROJECT);

            verifyNoInteractions(memberRepository, accessPolicy);
        }

        @Test
        @DisplayName("할 일 수정 성공 시 댓글/파일 개수 포함 검증")
        void test5() {
            // given
            UUID taskId = baseTask.getId();

            given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
            given(taskRepository.findById(taskId)).willReturn(Optional.of(baseTask));

            doNothing().when(accessPolicy).ensureProjectMember(eq(projectId), eq(userId));
            doNothing().when(accessPolicy).ensureAssigneesAreProjectMembers(eq(projectId), any());

            UUID tagId = UUID.randomUUID();
            Tag tag = Fixtures.tag(tagId, "태그1", project);
            Member member = Fixtures.member(UUID.randomUUID(), "테스터");

            given(tagRepository.findAllById(anyList())).willReturn(List.of(tag));
            given(memberRepository.findAllById(anyList())).willReturn(List.of(member));

            given(commentRepository.countByTaskId(taskId)).willReturn(5L);
            given(fileRepository.countByTaskId(taskId)).willReturn(3L);

            given(projectMembershipRepository.countByProjectId(projectId)).willReturn(4);

            TaskUpdateRequestDto request = Fixtures.reqUpdate(
                "수정제목",
                "수정설명",
                TaskStatus.REVIEW,
                Fixtures.DUE2,
                true,
                2,
                List.of(tagId),
                List.of(member.getId())
            );

            // when
            TaskResponseDto response = taskService.updateTask(projectId, taskId, request, user);

            // then
            assertThat(response.commentCount()).isEqualTo(5);
            assertThat(response.fileCount()).isEqualTo(3);
            assertThat(response.title()).isEqualTo("수정제목");
            assertThat(response.status()).isEqualTo(TaskStatus.REVIEW);
            verify(commentRepository).countByTaskId(taskId);
            verify(fileRepository).countByTaskId(taskId);
        }

        @Test
        @DisplayName("할 일 수정 실패 - 태그가 다른 프로젝트에 속함")
        void testTagFromAnotherProject() {
            // given
            UUID taskId = baseTask.getId();
            given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
            given(taskRepository.findById(taskId)).willReturn(Optional.of(baseTask));

            Project otherProject = Fixtures.project(UUID.randomUUID());
            Tag invalidTag = Fixtures.tag(UUID.randomUUID(), "잘못된태그", otherProject);

            given(tagRepository.findAllById(anyList())).willReturn(List.of(invalidTag));
            doNothing().when(accessPolicy).ensureProjectMember(eq(projectId), eq(userId));

            TaskUpdateRequestDto request = Fixtures.reqUpdate(
                "제목",
                "설명",
                TaskStatus.TODO,
                Fixtures.DUE,
                false,
                1,
                List.of(invalidTag.getId()),
                List.of()
            );

            // when & then
            assertThatThrownBy(() -> taskService.updateTask(projectId, taskId, request, user))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TAG_NOT_IN_PROJECT);

            verify(commentRepository, never()).countByTaskId(any());
            verify(fileRepository, never()).countByTaskId(any());
        }

        @Test
        @DisplayName("할 일 수정 실패 - 필수 리뷰어 수가 가능한 리뷰어 수보다 많음")
        void testInvalidRequiredReviewerCount() {
            // given
            UUID taskId = baseTask.getId();

            given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
            given(taskRepository.findById(taskId)).willReturn(Optional.of(baseTask));

            doNothing().when(accessPolicy).ensureProjectMember(eq(projectId), eq(userId));
            doNothing().when(accessPolicy).ensureAssigneesAreProjectMembers(eq(projectId), any());

            given(projectMembershipRepository.countByProjectId(projectId)).willReturn(3);

            UUID m1 = UUID.randomUUID();
            UUID m2 = UUID.randomUUID();
            given(memberRepository.findAllById(anyList()))
                .willReturn(List.of(Fixtures.member(m1, "영진"), Fixtures.member(m2, "비버")));

            TaskUpdateRequestDto request = Fixtures.reqUpdate(
                "리뷰어 수 초과 수정",
                "필수 리뷰어 수가 가능한 리뷰어 수보다 많으면 예외 발생",
                TaskStatus.TODO,
                Fixtures.DUE2,
                false,
                2,
                List.of(),
                List.of(m1, m2)
            );

            // when & then
            assertThatThrownBy(() -> taskService.updateTask(projectId, taskId, request, user))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode",
                    ErrorCode.INVALID_REQUIRED_REVIEWER_COUNT);

            verify(taskRepository, never()).save(any());
            verify(commentRepository, never()).countByTaskId(any());
            verify(fileRepository, never()).countByTaskId(any());
        }

        @Test
        @DisplayName("할 일 수정 성공 - 프로젝트 멤버면 비담당자여도 가능")
        void updateTask_success_projectMemberCanEditWithoutAssigneeRole() {
            // given
            UUID taskId = baseTask.getId();
            UUID anotherMemberId = UUID.randomUUID();
            UserPrincipalDto anotherUser = new UserPrincipalDto(
                anotherMemberId, "other-user", "https://example.com/avatar/other-user"
            );

            given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
            given(taskRepository.findById(taskId)).willReturn(Optional.of(baseTask));
            given(projectMembershipRepository.countByProjectId(projectId)).willReturn(4);

            doNothing().when(accessPolicy).ensureProjectMember(eq(projectId), eq(anotherMemberId));
            doNothing().when(accessPolicy)
                .ensureAssigneesAreProjectMembers(eq(projectId), any(Set.class));

            TaskUpdateRequestDto request = Fixtures.reqUpdate(
                "프로젝트 멤버 수정",
                "담당자가 아니어도 수정 가능",
                TaskStatus.PROGRESS,
                Fixtures.DUE2,
                false,
                1,
                List.of(),
                List.of()
            );

            // when
            TaskResponseDto response = taskService.updateTask(projectId, taskId, request, anotherUser);

            // then
            assertThat(response.title()).isEqualTo("프로젝트 멤버 수정");
            verify(accessPolicy).ensureProjectMember(projectId, anotherMemberId);
            verify(accessPolicy, never()).ensureTaskAssignee(taskId, anotherMemberId);
        }

    }

    @Nested
    @DisplayName("할 일 삭제")
    class DeleteTask {

        @Test
        @DisplayName("할 일 삭제 성공")
        void test1() {
            // given
            UUID taskId = baseTask.getId();

            given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
            given(taskRepository.findById(taskId)).willReturn(Optional.of(baseTask));

            doNothing().when(accessPolicy).ensureProjectMember(eq(projectId), eq(userId));

            // when
            taskService.deleteTask(projectId, taskId, user);

            // then
            verify(taskRepository).delete(baseTask);
        }

        @Test
        @DisplayName("할 일 삭제 실패 - 404 (프로젝트 없음)")
        void test2() {
            // given
            UUID taskId = UUID.randomUUID();
            given(projectRepository.findById(projectId)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> taskService.deleteTask(projectId, taskId, user))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_FOUND);

            verifyNoInteractions(taskRepository, accessPolicy);
        }

        @Test
        @DisplayName("할 일 삭제 실패 - 404 (할 일 없음)")
        void test3() {
            // given
            UUID taskId = UUID.randomUUID();

            given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
            given(taskRepository.findById(taskId)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> taskService.deleteTask(projectId, taskId, user))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TASK_NOT_FOUND);

            verify(taskRepository, never()).delete(any());
            verifyNoInteractions(accessPolicy);
        }

        @Test
        @DisplayName("할 일 삭제 실패 - 409 (다른 프로젝트의 할 일)")
        void test4() {
            // given
            UUID otherProjectId = UUID.randomUUID();
            Project otherProject = Fixtures.project(otherProjectId);
            given(projectRepository.findById(projectId)).willReturn(Optional.of(project));

            Task existing = Fixtures.task(UUID.randomUUID(), otherProject);
            given(taskRepository.findById(existing.getId())).willReturn(Optional.of(existing));

            // when & then
            assertThatThrownBy(() -> taskService.deleteTask(projectId, existing.getId(), user))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TASK_NOT_IN_PROJECT);

            verify(taskRepository, never()).delete(any());
            verifyNoInteractions(accessPolicy);
        }

        @Test
        @DisplayName("할 일 삭제 성공 - 프로젝트 멤버면 비담당자여도 가능")
        void deleteTask_success_projectMemberCanDeleteWithoutAssigneeRole() {
            // given
            UUID taskId = baseTask.getId();
            UUID anotherMemberId = UUID.randomUUID();
            UserPrincipalDto anotherUser = new UserPrincipalDto(
                anotherMemberId, "other-user", "https://example.com/avatar/other-user"
            );

            given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
            given(taskRepository.findById(taskId)).willReturn(Optional.of(baseTask));

            doNothing().when(accessPolicy).ensureProjectMember(eq(projectId), eq(anotherMemberId));

            // when
            taskService.deleteTask(projectId, taskId, anotherUser);

            // then
            verify(taskRepository).delete(baseTask);
            verify(accessPolicy).ensureProjectMember(projectId, anotherMemberId);
            verify(accessPolicy, never()).ensureTaskAssignee(taskId, anotherMemberId);
        }
    }

    @Nested
    @DisplayName("할 일 상태 변경")
    class ChangeStatus {

        @Test
        @DisplayName("할 일 상태 변경 성공")
        void test1() {
            // given
            UUID taskId = baseTask.getId();

            given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
            given(taskRepository.findById(taskId)).willReturn(Optional.of(baseTask));

            doNothing().when(accessPolicy).ensureProjectMember(eq(projectId), eq(userId));

            TaskStatusRequestDto request = Fixtures.reqStatus(TaskStatus.REVIEW);

            // when
            TaskResponseDto response = taskService.changeTaskStatus(
                projectId, taskId, request, user
            );

            // then
            assertThat(response.status()).isEqualTo(TaskStatus.REVIEW);
            assertThat(baseTask.getStatus()).isEqualTo(TaskStatus.REVIEW);
        }

        @Test
        @DisplayName("할 일 상태 변경 실패 - 404 (프로젝트 없음)")
        void test2() {
            // given
            UUID taskId = UUID.randomUUID();
            given(projectRepository.findById(projectId)).willReturn(Optional.empty());

            TaskStatusRequestDto request = Fixtures.reqStatus(TaskStatus.DONE);

            // when & then
            assertThatThrownBy(() -> taskService.changeTaskStatus(projectId, taskId, request, user))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_FOUND);

            verifyNoInteractions(taskRepository, accessPolicy);
        }

        @Test
        @DisplayName("할 일 상태 변경 실패 - 404 (할 일 없음)")
        void test3() {
            // given
            UUID taskId = UUID.randomUUID();
            given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
            given(taskRepository.findById(taskId)).willReturn(Optional.empty());

            TaskStatusRequestDto request = Fixtures.reqStatus(TaskStatus.DONE);

            // when & then
            assertThatThrownBy(() -> taskService.changeTaskStatus(projectId, taskId, request, user))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TASK_NOT_FOUND);

            verifyNoInteractions(accessPolicy);
        }

        @Test
        @DisplayName("할 일 상태 변경 실패 - 409 (다른 프로젝트의 할 일)")
        void test4() {
            // given
            UUID anotherProjectId = UUID.randomUUID();
            Project otherProject = Fixtures.project(anotherProjectId);
            given(projectRepository.findById(projectId)).willReturn(Optional.of(project));

            Task existing = Fixtures.task(UUID.randomUUID(), otherProject);
            given(taskRepository.findById(existing.getId())).willReturn(Optional.of(existing));

            TaskStatusRequestDto request = Fixtures.reqStatus(TaskStatus.DONE);

            // when & then
            assertThatThrownBy(
                () -> taskService.changeTaskStatus(projectId, existing.getId(), request, user))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TASK_NOT_IN_PROJECT);

            verifyNoInteractions(accessPolicy);
        }

        @Test
        @DisplayName("할 일 상태 변경 성공 - 프로젝트 멤버면 비담당자여도 가능")
        void changeStatus_success_projectMemberCanChangeWithoutAssigneeRole() {
            // given
            UUID taskId = baseTask.getId();
            UUID anotherMemberId = UUID.randomUUID();
            UserPrincipalDto anotherUser = new UserPrincipalDto(
                anotherMemberId, "other-user", "https://example.com/avatar/other-user"
            );

            given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
            given(taskRepository.findById(taskId)).willReturn(Optional.of(baseTask));

            doNothing().when(accessPolicy).ensureProjectMember(eq(projectId), eq(anotherMemberId));

            TaskStatusRequestDto request = Fixtures.reqStatus(TaskStatus.REVIEW);

            // when
            TaskResponseDto response = taskService.changeTaskStatus(
                projectId, taskId, request, anotherUser
            );

            // then
            assertThat(response.status()).isEqualTo(TaskStatus.REVIEW);
            verify(accessPolicy).ensureProjectMember(projectId, anotherMemberId);
            verify(accessPolicy, never()).ensureTaskAssignee(taskId, anotherMemberId);
        }
    }

    @Nested
    @DisplayName("할 일 승인 및 재검토 요청 (Review 관련)")
    class Review {

        @Nested
        @DisplayName("할 일 승인")
        class ApproveTask {

            @Test
            @DisplayName("성공")
            void success_approve() {
                UUID taskId = baseTask.getId();
                Task task = baseTask;
                task.changeStatus(TaskStatus.REVIEW);

                given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
                given(taskRepository.findById(taskId)).willReturn(Optional.of(task));
                doNothing().when(accessPolicy).ensureProjectMember(projectId, user.id());

                Member member = Fixtures.member(user.id(), "승인자");
                given(memberRepository.findById(user.id())).willReturn(Optional.of(member));
                given(projectMembershipRepository.findAllByProjectId(projectId))
                    .willReturn(List.of(Fixtures.projectMembership(project, member)));

                // when
                var response = taskService.approveTask(projectId, taskId, user);

                // then
                assertThat(response.taskId()).isEqualTo(taskId);
            }

            @Test
            @DisplayName("실패 - 프로젝트 없음")
            void projectNotFound() {
                given(projectRepository.findById(projectId)).willReturn(Optional.empty());

                assertThatThrownBy(
                    () -> taskService.approveTask(projectId, UUID.randomUUID(), user))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_FOUND);
            }

            @Test
            @DisplayName("실패 - 할 일 없음")
            void taskNotFound() {
                given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
                given(taskRepository.findById(any())).willReturn(Optional.empty());

                assertThatThrownBy(
                    () -> taskService.approveTask(projectId, UUID.randomUUID(), user))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TASK_NOT_FOUND);
            }

            @Test
            @DisplayName("실패 - 멤버 없음")
            void memberNotFound() {
                UUID taskId = baseTask.getId();
                given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
                given(taskRepository.findById(taskId)).willReturn(Optional.of(baseTask));
                given(memberRepository.findById(user.id())).willReturn(Optional.empty());

                assertThatThrownBy(() -> taskService.approveTask(projectId, taskId, user))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEMBER_NOT_FOUND);
            }

            @Test
            @DisplayName("성공 - 낙관적 락이 정상 동작함")
            void optimisticLockWorking() {
                // given
                UUID taskId = baseTask.getId();
                Task task = baseTask;
                task.changeStatus(TaskStatus.REVIEW);

                given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
                given(taskRepository.findById(taskId)).willReturn(Optional.of(task));
                doNothing().when(accessPolicy).ensureProjectMember(projectId, user.id());

                Member member = Fixtures.member(user.id(), "승인자");
                given(memberRepository.findById(user.id())).willReturn(Optional.of(member));
                given(projectMembershipRepository.findAllByProjectId(projectId))
                    .willReturn(List.of(Fixtures.projectMembership(project, member)));

                // when
                var response = taskService.approveTask(projectId, taskId, user);

                // then
                assertThat(response.taskId()).isEqualTo(taskId);
                assertThat(task.getApprovers()).hasSize(1);
                assertThat(task.getApprovers()).contains(member);
            }
        }

        @Nested
        @DisplayName("재리뷰 요청")
        class RequestReReview {

            @Test
            @DisplayName("성공 - REVIEW 상태에서 이벤트 발행")
            void success_reviewStatus() {
                // given
                UUID taskId = baseTask.getId();
                baseTask.changeStatus(TaskStatus.REVIEW);

                given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
                given(taskRepository.findById(taskId)).willReturn(Optional.of(baseTask));

                doNothing().when(accessPolicy).ensureProjectMember(projectId, user.id());
                doNothing().when(accessPolicy).ensureTaskAssignee(taskId, user.id());
                doNothing().when(taskEventPublisher).publishTaskReReviewEvent(projectId, taskId);

                // when
                taskService.requestReReview(projectId, taskId, user);

                // then
                verify(taskEventPublisher).publishTaskReReviewEvent(projectId, taskId);
            }

            @Test
            @DisplayName("실패 - 상태가 REVIEW가 아님")
            void fail_notReviewStatus() {
                // given
                UUID taskId = baseTask.getId();
                baseTask.changeStatus(TaskStatus.TODO);

                given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
                given(taskRepository.findById(taskId)).willReturn(Optional.of(baseTask));

                doNothing().when(accessPolicy).ensureProjectMember(projectId, user.id());
                doNothing().when(accessPolicy).ensureTaskAssignee(taskId, user.id());

                // when & then
                assertThatThrownBy(() -> taskService.requestReReview(projectId, taskId, user))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TASK_RE_REVIEW_NOT_ALLOWED);

                verify(taskEventPublisher, never()).publishTaskReReviewEvent(any(), any());
            }

            @Test
            @DisplayName("실패 - 프로젝트 없음")
            void projectNotFound() {
                given(projectRepository.findById(projectId)).willReturn(Optional.empty());

                assertThatThrownBy(
                    () -> taskService.requestReReview(projectId, UUID.randomUUID(), user))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_FOUND);
            }

            @Test
            @DisplayName("실패 - 할 일 없음")
            void taskNotFound() {
                given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
                given(taskRepository.findById(any())).willReturn(Optional.empty());

                assertThatThrownBy(
                    () -> taskService.requestReReview(projectId, UUID.randomUUID(), user))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TASK_NOT_FOUND);
            }
        }
    }

    @Nested
    @DisplayName("할 일 개수 조회 (Count 관련)")
    class CountQuery {

        @Nested
        @DisplayName("내 할 일 상태별 개수 조회")
        class CountMyTasksByStatus {

            @Test
            @DisplayName("성공 - 검색어 없음")
            void success_withoutSearch() {
                // given
                UUID memberId = user.id();
                Member member = Fixtures.member(memberId, "테스터");
                given(memberRepository.findById(memberId)).willReturn(Optional.of(member));

                Project project1 = Fixtures.project(UUID.randomUUID());
                Project project2 = Fixtures.project(UUID.randomUUID());
                given(projectMembershipRepository.findAllByMemberId(memberId))
                    .willReturn(List.of(
                        Fixtures.projectMembership(project1, member),
                        Fixtures.projectMembership(project2, member)
                    ));

                ProjectTaskStatusCount count = new ProjectTaskStatusCount(3, 2, 1, 4);
                given(taskRepository.countMyTasksGrouped(memberId, List.of(project1, project2)))
                    .willReturn(count);

                // when
                var response = taskService.countMyTasksByStatus(null, user);

                // then
                assertThat(response.memberId()).isEqualTo(memberId);
                assertThat(response.todo()).isEqualTo(3);
                assertThat(response.progress()).isEqualTo(2);
                assertThat(response.review()).isEqualTo(1);
                assertThat(response.done()).isEqualTo(4);
            }

            @Test
            @DisplayName("성공 - 검색어 포함")
            void success_withSearch() {
                UUID memberId = user.id();
                Member member = Fixtures.member(memberId, "테스터");
                given(memberRepository.findById(memberId)).willReturn(Optional.of(member));

                Project project1 = Fixtures.project(UUID.randomUUID());
                given(projectMembershipRepository.findAllByMemberId(memberId))
                    .willReturn(List.of(Fixtures.projectMembership(project1, member)));

                ProjectTaskStatusCount count = new ProjectTaskStatusCount(1, 2, 3, 4);
                given(taskRepository.countMyTasksWithSearchGrouped(eq(memberId), anyList(),
                    anyString()))
                    .willReturn(count);

                var response = taskService.countMyTasksByStatus("테스트", user);

                assertThat(response.todo()).isEqualTo(1);
                assertThat(response.progress()).isEqualTo(2);
                assertThat(response.review()).isEqualTo(3);
                assertThat(response.done()).isEqualTo(4);
            }

            @Test
            @DisplayName("실패 - 멤버 없음")
            void memberNotFound() {
                given(memberRepository.findById(user.id())).willReturn(Optional.empty());

                assertThatThrownBy(() -> taskService.countMyTasksByStatus(null, user))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEMBER_NOT_FOUND);
            }

            @Test
            @DisplayName("성공 - 속한 프로젝트가 없을 때 0 반환")
            void noProjects() {
                Member member = Fixtures.member(user.id(), "테스터");
                given(memberRepository.findById(user.id())).willReturn(Optional.of(member));
                given(projectMembershipRepository.findAllByMemberId(user.id()))
                    .willReturn(List.of());

                var response = taskService.countMyTasksByStatus(null, user);

                assertThat(response.todo()).isZero();
                assertThat(response.progress()).isZero();
                assertThat(response.review()).isZero();
                assertThat(response.done()).isZero();
            }
        }

        @Nested
        @DisplayName("프로젝트별 상태별 개수 조회")
        class CountTasksByStatusForProject {

            @Test
            @DisplayName("성공 - 검색어 없음")
            void success_withoutSearch() {
                given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
                doNothing().when(accessPolicy).ensureProjectMember(projectId, user.id());

                ProjectTaskStatusCount count = new ProjectTaskStatusCount(2, 4, 1, 3);
                given(taskRepository.countByProjectGrouped(projectId)).willReturn(count);

                var response = taskService.countTasksByStatusForProject(projectId, null, user);

                assertThat(response.projectId()).isEqualTo(projectId);
                assertThat(response.todo()).isEqualTo(2);
                assertThat(response.progress()).isEqualTo(4);
                assertThat(response.review()).isEqualTo(1);
                assertThat(response.done()).isEqualTo(3);
            }

            @Test
            @DisplayName("성공 - 검색어 포함")
            void success_withSearch() {
                given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
                doNothing().when(accessPolicy).ensureProjectMember(projectId, user.id());

                ProjectTaskStatusCount count = new ProjectTaskStatusCount(5, 6, 7, 8);
                given(taskRepository.countByProjectWithSearchGrouped(eq(projectId), anyString()))
                    .willReturn(count);

                var response = taskService.countTasksByStatusForProject(projectId, "검색", user);

                assertThat(response.todo()).isEqualTo(5);
                assertThat(response.progress()).isEqualTo(6);
                assertThat(response.review()).isEqualTo(7);
                assertThat(response.done()).isEqualTo(8);
            }

            @Test
            @DisplayName("실패 - 프로젝트 없음")
            void projectNotFound() {
                given(projectRepository.findById(projectId)).willReturn(Optional.empty());

                assertThatThrownBy(
                    () -> taskService.countTasksByStatusForProject(projectId, null, user))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_FOUND);
            }
        }

        @Nested
        @DisplayName("모든 멤버별 상태별 개수 조회")
        class CountTasksByStatusForAllMembers {

            @Test
            @DisplayName("성공 - 검색어 없음")
            void success_withoutSearch() {
                given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
                doNothing().when(accessPolicy).ensureProjectMember(projectId, user.id());

                MemberTaskStatusCount c1 = new MemberTaskStatusCount(UUID.randomUUID(), 1, 2, 3);
                MemberTaskStatusCount c2 = new MemberTaskStatusCount(UUID.randomUUID(), 4, 5, 6);
                given(taskRepository.countTasksByStatusForAllMembersGrouped(projectId))
                    .willReturn(List.of(c1, c2));

                var response = taskService.countTasksByStatusForAllMembers(projectId, null, user);

                assertThat(response).hasSize(2);
                assertThat(response.get(0).todo()).isEqualTo(1);
                assertThat(response.get(1).review()).isEqualTo(6);
            }

            @Test
            @DisplayName("성공 - 검색어 포함")
            void success_withSearch() {
                given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
                doNothing().when(accessPolicy).ensureProjectMember(projectId, user.id());

                MemberTaskStatusCount c1 = new MemberTaskStatusCount(UUID.randomUUID(), 2, 3, 4);
                given(taskRepository.countTasksByStatusForAllMembersWithSearchGrouped(eq(projectId),
                    anyString()))
                    .willReturn(List.of(c1));

                var response = taskService.countTasksByStatusForAllMembers(projectId, "검색", user);

                assertThat(response).hasSize(1);
                assertThat(response.getFirst().todo()).isEqualTo(2);
                assertThat(response.getFirst().progress()).isEqualTo(3);
                assertThat(response.getFirst().review()).isEqualTo(4);
            }

            @Test
            @DisplayName("실패 - 프로젝트 없음")
            void projectNotFound() {
                given(projectRepository.findById(projectId)).willReturn(Optional.empty());

                assertThatThrownBy(
                    () -> taskService.countTasksByStatusForAllMembers(projectId, null, user))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_FOUND);
            }
        }
    }

    @Nested
    @DisplayName("할 일 목록 조회")
    class ListTasks {

        @Nested
        @DisplayName("상태별 목록 조회 (listByStatus)")
        class ListByStatus {

            @Test
            @DisplayName("성공 - CREATED_AT ASC 정렬, 검색 없음")
            void success_createdAtAsc() {
                given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
                doNothing().when(accessPolicy).ensureProjectMember(projectId, user.id());

                Task task1 = Fixtures.task(UUID.randomUUID(), project);
                given(taskRepository.findByStatusOrderByCreatedAtAsc(
                    eq(project), isNull(), eq(TaskStatus.TODO), any(), any(), any()
                )).willReturn(List.of(task1));

                given(fileRepository.countByTaskIds(anyList()))
                    .willReturn(List.of(mockFileCount(task1.getId(), 1L)));
                given(commentRepository.countByTaskIds(anyList()))
                    .willReturn(List.of(mockCommentCount(task1.getId(), 2L)));

                var res = taskService.listByStatus(
                    projectId, null, TaskStatus.TODO,
                    TaskSortBy.CREATED_AT, TaskSortDirection.ASC,
                    null, null, 10, user
                );

                assertThat(res.tasks()).hasSize(1);
                assertThat(res.hasNext()).isFalse();
            }

            @Test
            @DisplayName("성공 - DUE_DATE DESC 정렬, 검색 포함")
            void success_dueDateDesc_withSearch() {
                given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
                doNothing().when(accessPolicy).ensureProjectMember(projectId, user.id());

                Task task = Fixtures.task(UUID.randomUUID(), project);
                given(taskRepository.findByStatusWithSearchOrderByDueDateDesc(
                    eq(project), isNull(), eq(TaskStatus.TODO), anyString(), any(), any(), any()
                )).willReturn(List.of(task));

                given(fileRepository.countByTaskIds(anyList()))
                    .willReturn(List.of(mockFileCount(task.getId(), 3L)));
                given(commentRepository.countByTaskIds(anyList()))
                    .willReturn(List.of(mockCommentCount(task.getId(), 1L)));

                var res = taskService.listByStatus(
                    projectId, null, TaskStatus.TODO,
                    TaskSortBy.DUE_DATE, TaskSortDirection.DESC,
                    "검색어", null, 10, user
                );

                assertThat(res.tasks()).hasSize(1);
            }

            @Test
            @DisplayName("성공 - 태그 필터가 있는 경우")
            void success_withTagFilter() {
                UUID tagId = UUID.randomUUID();
                given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
                doNothing().when(accessPolicy).ensureProjectMember(projectId, user.id());

                Task task = Fixtures.task(UUID.randomUUID(), project);
                given(taskRepository.findByStatusOrderByCreatedAtDesc(
                    eq(project), eq(tagId), eq(TaskStatus.TODO), any(), any(), any()
                )).willReturn(List.of(task));

                given(fileRepository.countByTaskIds(anyList()))
                    .willReturn(List.of(mockFileCount(task.getId(), 1L)));
                given(commentRepository.countByTaskIds(anyList()))
                    .willReturn(List.of(mockCommentCount(task.getId(), 2L)));

                var res = taskService.listByStatus(
                    projectId, tagId, TaskStatus.TODO,
                    TaskSortBy.CREATED_AT, TaskSortDirection.DESC,
                    null, null, 10, user
                );

                assertThat(res.tasks()).hasSize(1);
            }

            @Test
            @DisplayName("실패 - PROJECT_NOT_FOUND")
            void fail_projectNotFound() {
                given(projectRepository.findById(projectId)).willReturn(Optional.empty());
                assertThatThrownBy(() -> taskService.listByStatus(
                    projectId, null, TaskStatus.TODO,
                    TaskSortBy.CREATED_AT, TaskSortDirection.ASC,
                    null, null, 10, user
                ))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_FOUND);
            }
        }

        @Nested
        @DisplayName("내 할 일 목록 조회 (listMyTasksByStatus)")
        class ListMyTasksByStatus {

            @Test
            @DisplayName("성공 - CREATED_AT DESC 정렬 + 검색 포함")
            void success_withSearch() {
                Member member = Fixtures.member(userId, "테스터");
                given(memberRepository.findById(userId)).willReturn(Optional.of(member));
                given(projectMembershipRepository.findAllByMemberId(userId))
                    .willReturn(List.of(Fixtures.projectMembership(project, member)));

                Task task = Fixtures.task(UUID.randomUUID(), project);
                given(taskRepository.findMyTasksWithSearchOrderByCreatedAtDesc(
                    anyList(), eq(member), eq(TaskStatus.TODO),
                    anyString(), any(), any(), any()
                )).willReturn(List.of(task));

                given(fileRepository.countByTaskIds(anyList()))
                    .willReturn(List.of(mockFileCount(task.getId(), 2L)));
                given(commentRepository.countByTaskIds(anyList()))
                    .willReturn(List.of(mockCommentCount(task.getId(), 4L)));

                var res = taskService.listMyTasksByStatus(
                    TaskStatus.TODO, TaskSortBy.CREATED_AT, TaskSortDirection.DESC,
                    "검색", null, 10, user
                );

                assertThat(res.tasks()).hasSize(1);
            }

            @Test
            @DisplayName("실패 - MEMBER_NOT_FOUND")
            void fail_memberNotFound() {
                given(memberRepository.findById(userId)).willReturn(Optional.empty());
                assertThatThrownBy(() -> taskService.listMyTasksByStatus(
                    TaskStatus.TODO, TaskSortBy.CREATED_AT, TaskSortDirection.ASC,
                    null, null, 10, user
                ))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEMBER_NOT_FOUND);
            }
        }

        @Nested
        @DisplayName("멤버별 할 일 목록 조회 (listByMember)")
        class ListByMember {

            @Test
            @DisplayName("성공 - 검색 포함, 커서 기반")
            void success_withSearchAndCursor() {
                UUID memberId = UUID.randomUUID();
                Member member = Fixtures.member(memberId, "홍길동");
                ProjectMembership pm = Fixtures.projectMembership(project, member);
                given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
                doNothing().when(accessPolicy).ensureProjectMember(projectId, user.id());
                given(projectMembershipRepository.findByProjectIdAndMemberId(projectId, memberId))
                    .willReturn(Optional.of(pm));

                Task task = Fixtures.task(UUID.randomUUID(), project);
                given(taskRepository.findTasksByAssigneeWithSearchAndCursor(
                    eq(member), eq(project), anyString(),
                    any(), any(), any(), any()
                )).willReturn(List.of(task));

                given(fileRepository.countByTaskIds(anyList()))
                    .willReturn(List.of(mockFileCount(task.getId(), 2L)));
                given(commentRepository.countByTaskIds(anyList()))
                    .willReturn(List.of(mockCommentCount(task.getId(), 5L)));

                var res = taskService.listByMember(
                    projectId, memberId, "검색어", null, 10, user
                );

                assertThat(res.member().id()).isEqualTo(memberId);
                assertThat(res.tasks()).hasSize(1);
            }

            @Test
            @DisplayName("실패 - MEMBER_NOT_FOUND (프로젝트에 속하지 않음)")
            void fail_memberNotInProject() {
                UUID memberId = UUID.randomUUID();
                given(projectRepository.findById(projectId)).willReturn(Optional.of(project));
                doNothing().when(accessPolicy).ensureProjectMember(projectId, user.id());
                given(projectMembershipRepository.findByProjectIdAndMemberId(projectId, memberId))
                    .willReturn(Optional.empty());

                assertThatThrownBy(() -> taskService.listByMember(
                    projectId, memberId, null, null, 10, user
                ))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEMBER_NOT_FOUND);
            }
        }

        private FileRepository.FileCount mockFileCount(UUID taskId, Long count) {
            return new FileRepository.FileCount() {
                public UUID getTaskId() {
                    return taskId;
                }

                public Long getCount() {
                    return count;
                }
            };
        }

        private CommentRepository.CommentCount mockCommentCount(UUID taskId, Long count) {
            return new CommentRepository.CommentCount() {
                public UUID getTaskId() {
                    return taskId;
                }

                public Long getCount() {
                    return count;
                }
            };
        }
    }

    static class Fixtures {

        static final LocalDate DUE = LocalDate.of(2025, 9, 17);
        static final LocalDate DUE2 = LocalDate.of(2025, 10, 1);

        static Project project(UUID id) {
            return Project.builder().id(id).name("테스트 프로젝트").build();
        }

        static Member member(UUID id, String name) {
            return Member.builder().id(id).name(name).build();
        }

        static Tag tag(UUID id, String name, Project project) {
            return Tag.builder().id(id).name(name).project(project).build();
        }

        static ProjectMembership projectMembership(Project project, Member member) {
            return ProjectMembership.builder()
                .project(project)
                .member(member)
                .build();
        }

        static Task task(UUID taskId, Project project) {
            Tag tag1 = Tag.builder().id(UUID.randomUUID()).name("피드백").build();
            Tag tag2 = Tag.builder().id(UUID.randomUUID()).name("멘토링").build();

            return Task.builder()
                .id(taskId)
                .project(project)
                .title("1회차 기술 멘토링 피드백 반영")
                .description("기술 멘토링에서 나온 멘토님의 피드백을 반영한다.")
                .status(TaskStatus.TODO)
                .dueDate(DUE)
                .urgent(false)
                .requiredReviewerCount(1)
                .tags(Set.of(tag1, tag2))
                .assignees(Set.of())
                .build();
        }

        static TaskCreateRequestDto reqCreate(
            String title,
            String desc,
            TaskStatus status,
            LocalDate due,
            boolean urgent,
            int reviewers,
            List<UUID> tags,
            List<UUID> assignees
        ) {
            return new TaskCreateRequestDto(
                title, desc, status, due, urgent, reviewers, tags, assignees
            );
        }

        static TaskUpdateRequestDto reqUpdate(
            String title,
            String desc,
            TaskStatus status,
            LocalDate due,
            boolean urgent,
            int reviewers,
            List<UUID> tags,
            List<UUID> assignees
        ) {
            return new TaskUpdateRequestDto(
                title, desc, status, due, urgent, reviewers, tags, assignees
            );
        }

        static TaskStatusRequestDto reqStatus(TaskStatus status) {
            return new TaskStatusRequestDto(status);
        }
    }
}
