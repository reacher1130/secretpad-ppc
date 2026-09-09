package org.secretflow.secretpad.service.test;

import org.junit.jupiter.api.Test;
import org.secretflow.secretpad.common.exception.SecretpadException;
import org.secretflow.secretpad.persistence.repository.ProjectRepository;
import org.secretflow.secretpad.service.impl.ProjectServiceImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeProjectIsolationTest {
    @Test
    void nativeServiceMustNotDependOnBfiaProjectProvider() {
        assertFalse(Arrays.stream(ProjectServiceImpl.class.getDeclaredFields())
                .anyMatch(field -> field.getGenericType().getTypeName().contains("bfia")),
                "Native project lists must not merge BFIA projects");
    }

    @Test
    void missingNativeProjectDoesNotFallbackToAnotherEngine() {
        var repository = mock(ProjectRepository.class);
        var service = new ProjectServiceImpl();
        ReflectionTestUtils.setField(service, "projectRepository", repository);
        when(repository.findById("bfia-only-project")).thenReturn(Optional.empty());
        assertThrows(SecretpadException.class, () -> service.getProject("bfia-only-project"));
        verify(repository).findById("bfia-only-project");
    }
}
