package knu.team1.be.boost.file.repository;

import java.util.List;
import java.util.UUID;
import knu.team1.be.boost.file.entity.File;
import knu.team1.be.boost.task.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FileRepository extends JpaRepository<File, UUID> {

    List<File> findAllByTask(Task task);

    @Query("""
            SELECT f
            FROM File f
            JOIN f.task t
            WHERE t.project.id = :projectId
              AND f.status = knu.team1.be.boost.file.entity.FileStatus.COMPLETED
            ORDER BY f.createdAt DESC, f.id DESC
        """)
    List<File> findAllByProjectId(@Param("projectId") UUID projectId);

    @Query("""
            SELECT COUNT(f)
            FROM File f
            WHERE f.task.id = :taskId
                AND f.status = knu.team1.be.boost.file.entity.FileStatus.COMPLETED
        """)
    long countByTaskId(@Param("taskId") UUID taskId);

    @Query("""
            SELECT f.task.id AS taskId, COUNT(f) AS count
            FROM File f
            WHERE f.task.id IN :taskIds
              AND f.status = knu.team1.be.boost.file.entity.FileStatus.COMPLETED
            GROUP BY f.task.id
        """)
    List<FileCount> countByTaskIds(@Param("taskIds") List<UUID> taskIds);

    interface FileCount {

        UUID getTaskId();

        Long getCount();
    }

    @Query("""
            SELECT COUNT(f)
            FROM File f
            WHERE f.task.project.id = :projectId
              AND f.status = knu.team1.be.boost.file.entity.FileStatus.COMPLETED
        """)
    long countByProject(@Param("projectId") UUID projectId);

    @Query("""
            SELECT COALESCE(SUM(f.metadata.sizeBytes), 0)
            FROM File f
            WHERE f.task.project.id = :projectId
              AND f.status = knu.team1.be.boost.file.entity.FileStatus.COMPLETED
        """)
    long sumSizeByProject(@Param("projectId") UUID projectId);
}
