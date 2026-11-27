package com.sapsr.backend.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/** Комментарий преподавателя, извлечённый из аннотации размеченного PDF (api-v1.md, раздел 10). */
@Entity
@Table(name = "annotations")
public class Annotation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "review_file_id", nullable = false)
    private Integer reviewFileId;

    // Порядок показа: по страницам, сверху вниз, ответы — сразу после комментария, на который отвечают.
    @Column(name = "position", nullable = false)
    private Integer position;

    @Column(name = "page", nullable = false)
    private Integer page;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "author")
    private String author;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;

    @Column(name = "quoted_text", columnDefinition = "TEXT")
    private String quotedText;

    // [x1, y1, x2, y2] в пунктах PDF, начало координат — левый нижний угол страницы.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rect", columnDefinition = "jsonb")
    private String rect;

    @Column(name = "reply_to_id")
    private Integer replyToId;

    public Annotation() {}

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getReviewFileId() { return reviewFileId; }
    public void setReviewFileId(Integer reviewFileId) { this.reviewFileId = reviewFileId; }
    public Integer getPosition() { return position; }
    public void setPosition(Integer position) { this.position = position; }
    public Integer getPage() { return page; }
    public void setPage(Integer page) { this.page = page; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public String getQuotedText() { return quotedText; }
    public void setQuotedText(String quotedText) { this.quotedText = quotedText; }
    public String getRect() { return rect; }
    public void setRect(String rect) { this.rect = rect; }
    public Integer getReplyToId() { return replyToId; }
    public void setReplyToId(Integer replyToId) { this.replyToId = replyToId; }
}
