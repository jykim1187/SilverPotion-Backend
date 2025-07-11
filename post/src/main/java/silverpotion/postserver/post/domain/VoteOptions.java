package silverpotion.postserver.post.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Builder
public class VoteOptions {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String optionText; //투표 항목

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vote_id")
    @JsonIgnore
    private Vote vote;

    @OneToMany(mappedBy = "voteOption", cascade = CascadeType.REMOVE, orphanRemoval = true)
    @JsonManagedReference
    private List<VoteAnswer> answers = new ArrayList<>();

    public void updateVote(Vote vote) {
        this.vote = vote;
    }

}
