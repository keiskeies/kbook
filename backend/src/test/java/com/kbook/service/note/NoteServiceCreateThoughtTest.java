package com.kbook.service.note;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.dto.note.NoteCreateRequest;
import com.kbook.dto.note.NoteVO;
import com.kbook.entity.Note;
import com.kbook.repository.NoteRepository;
import com.kbook.repository.NoteRevisionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NoteServiceCreateThoughtTest {

    private NoteRepository noteRepository;
    private NoteService noteService;

    @BeforeEach
    void setUp() {
        noteRepository = mock(NoteRepository.class);
        when(noteRepository.save(any(Note.class))).thenAnswer(invocation -> {
            Note note = invocation.getArgument(0);
            note.setId(1L);
            return note;
        });
        noteService = new NoteService(
                noteRepository,
                mock(NoteRevisionRepository.class),
                mock(NoteEmbeddingService.class),
                new ObjectMapper());
    }

    @Test
    @DisplayName("标题留空时从正文首行生成")
    void fillsTitleWhenBlank() {
        NoteCreateRequest request = new NoteCreateRequest();
        request.setContentMd("# 今天读到一句话，关于认知偏差\n第二行");
        request.setTags(List.of());

        NoteVO result = noteService.createThought(1L, request);

        assertThat(result.getTitle()).isEqualTo("今天读到一句话，关于认知偏差");
        assertThat(result.getContentMd()).isEqualTo(request.getContentMd());
    }

    @Test
    @DisplayName("用户填写标题时不被覆盖")
    void keepsUserTitle() {
        NoteCreateRequest request = new NoteCreateRequest();
        request.setTitle("我自己想的标题");
        request.setContentMd("正文第一行");
        request.setTags(List.of());

        NoteVO result = noteService.createThought(1L, request);

        assertThat(result.getTitle()).isEqualTo("我自己想的标题");
    }

    @Test
    @DisplayName("正文为空时标题保持为空")
    void noTitleWithoutContent() {
        NoteCreateRequest request = new NoteCreateRequest();
        request.setTitle("   ");
        request.setContentMd("   ");
        request.setTags(List.of("思考"));

        NoteVO result = noteService.createThought(1L, request);

        assertThat(result.getTitle()).isNull();
        assertThat(result.getTags()).containsExactly("思考");
    }
}
