package silverpotion.postserver.gathering.domain;

import jakarta.persistence.*;
import lombok.*;
import silverpotion.postserver.common.domain.BaseTimeEntity;

@Entity
@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class GatheringPeople extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long Id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gathering_id")
    private Gathering gathering;    //모임id

    @Column(nullable = false)
    private Long userId;    //회원id

    @Column(nullable = false)
    private String greetingMessage;    //가입인사

    @Enumerated(EnumType.STRING)
    private Status status;      //상태

    public void updateStatus(Status status) {
        this.status = status;
    }
}
