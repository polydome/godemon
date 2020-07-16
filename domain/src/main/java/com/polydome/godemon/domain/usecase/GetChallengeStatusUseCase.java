package com.polydome.godemon.domain.usecase;

import com.polydome.godemon.domain.entity.Challenge;
import com.polydome.godemon.domain.entity.ChallengeStage;
import com.polydome.godemon.domain.entity.Challenger;
import com.polydome.godemon.domain.model.ChallengeStatus;
import com.polydome.godemon.domain.repository.ChallengeRepository;
import com.polydome.godemon.domain.repository.ChallengerRepository;
import com.polydome.godemon.domain.service.ChallengeService;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class GetChallengeStatusUseCase {
    private final ChallengerRepository challengerRepository;
    private final ChallengeRepository challengeRepository;
    private final ChallengeService challengeService;

    public ChallengeStatus execute(long discordId, int challengeId) {
        Challenger challenger = challengerRepository.findByDiscordId(discordId);
        Challenge challenge = challengeRepository.findChallenge(challengeId);
        challenge = challengeService.synchronizeChallenge(challenge.getId());

        return ChallengeStatus.builder()
                .ended(challenge.getStatus() == ChallengeStage.FAILED)
                .godToUsesLeft(challenge.getAvailableGods())
                .godsLeftCount(challenge.getAvailableGods().size())
                .wins(0)
                .loses(0)
                .build();
    }

}
