package com.polydome.godemon.discordbot;

import com.polydome.godemon.domain.entity.Challenge;
import com.polydome.godemon.domain.entity.Challenger;
import com.polydome.godemon.domain.entity.Proposition;
import com.polydome.godemon.domain.repository.ChallengeRepository;
import com.polydome.godemon.domain.repository.ChallengerRepository;
import com.polydome.godemon.domain.repository.PropositionRepository;
import com.polydome.godemon.domain.usecase.*;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;

import javax.annotation.Nonnull;
import javax.security.auth.login.LoginException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class Bot extends ListenerAdapter {
    private final ChallengerRepository challengerRepository = createChallengerRepositoryStub();
    private final ChallengeRepository challengeRepository = createChallengeRepositoryStub();
    private final GameRulesProvider gameRulesProvider = createGameRulesProviderStub();
    private final PropositionRepository propositionRepository = createPropositionRepositoryStub();
    private final GodsDataProvider godsDataProvider = createGodsDataProviderStub();

    public static void main(String[] args) throws LoginException {
        if (args.length < 1) {
            System.out.println("You have to provide a token as first argument!");
            System.exit(1);
        }

        JDABuilder.createLight(args[0], GatewayIntent.GUILD_MESSAGES, GatewayIntent.DIRECT_MESSAGES, GatewayIntent.GUILD_MESSAGE_REACTIONS)
                .addEventListeners(new Bot())
                .build();
    }

    private static class CommandInvocation {

        public String command;
        public String[] args;

        public CommandInvocation(String command, String[] args) {
            this.command = command;
            this.args = args;
        }
    }

    private static CommandInvocation parseMessage(String message, String prefix) {
        if (message.startsWith(prefix)) {
            String[] invocation = message.substring(prefix.length() + 1).split(" ");

            String[] args = new String[invocation.length - 1];
            if (invocation.length - 1 >= 0) System.arraycopy(invocation, 1, args, 0, invocation.length - 1);

            return new CommandInvocation(invocation[0], args);
        } else {
            return null;
        }
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot())
            return;

        Message msg = event.getMessage();
        CommandInvocation commandInvocation = parseMessage(msg.getContentRaw(), ";godemon");

        if (commandInvocation == null) {
            System.out.println("Unable to parse: " + msg.getContentRaw());
            return;
        }

        switch (commandInvocation.command) {
            case "challenge" -> onChallengeStatusRequested(event);
            case "me" -> onIntroduction(event, commandInvocation.args);
            case "request" -> onChallengeRequested(event);
        }
    }

    @Override
    public void onMessageReactionAdd(@Nonnull MessageReactionAddEvent event) {
        if (!event.getReaction().isSelf())
            onChallengeAccepted(event);
    }

    private void onChallengeStatusRequested(MessageReceivedEvent event) {
        MessageChannel channel = event.getChannel();

        GetChallengeStatusUseCase getChallengeStatusUseCase = new GetChallengeStatusUseCase(challengerRepository, challengeRepository);
        GetChallengeStatusUseCase.Result result = getChallengeStatusUseCase.execute(event.getAuthor().getIdLong());

        String message;

        if (result.status != null) {
            message = result.status.toString();
        } else {
            message = switch (result.error) {
                case CHALLENGER_NOT_REGISTERED -> "A Challenge? Don't you think I deserve a proper introduction first?";
                case CHALLENGE_NOT_ACTIVE -> "You have no active challenge. Do you want to begin?";
            };
        }

        channel.sendMessage(message).queue();
    }

    private void onIntroduction(MessageReceivedEvent event, String[] args) {
        MessageChannel channel = event.getChannel();

        IntroduceUseCase introduceUseCase = new IntroduceUseCase(challengerRepository);
        IntroduceUseCase.Result result = introduceUseCase.execute(event.getAuthor().getIdLong(), args[0]);

        String message;

        if (result.getError() == null) {
            message = String.format("%s, You have been acknowledged as %s!", event.getAuthor().getAsMention(), result.getNewName());
        } else {
            message = switch (result.getError()) {
                case CHALLENGER_ALREADY_REGISTERED -> "I already know you...";
            };
        }

        channel.sendMessage(message).queue();
    }

    private void onChallengeRequested(MessageReceivedEvent event) {
        MessageChannel channel = event.getChannel();

        StartChallengeUseCase startChallengeUseCase = new StartChallengeUseCase(
                challengerRepository,
                challengeRepository,
                gameRulesProvider,
                (min, max) -> ThreadLocalRandom.current().nextInt(min, max + 1),
                propositionRepository
        );

        channel.sendMessage("I'm picking some gods for you...").queue(
                message -> {
                    StartChallengeUseCase.Result result = startChallengeUseCase.execute(
                            event.getAuthor().getIdLong(),
                            message.getIdLong()
                    );

                    String content;

                    if (result.getError() == null) {
                        content = String.format(
                                "%s, choose your first god from the following:",
                                event.getAuthor().getAsMention()
                        );

                        List<GodData> godsData = Arrays.stream(result.getProposition().getGods())
                                .mapToObj(godsDataProvider::findById)
                                .collect(Collectors.toList());

                        message.editMessage(content).queue(sentMessage -> {
                            for (GodData godData : godsData) {
                                sentMessage.addReaction(godData.getEmoteId()).queue();
                            }
                        });
                    } else {
                        content = switch (result.getError()) {
                            case CHALLENGE_ALREADY_ACTIVE -> "You are not done yet!";
                            case CHALLENGER_NOT_REGISTERED -> "A Challenge? Don't you think I deserve a proper introduction first?";
                            case CHALLENGE_ALREADY_PROPOSED -> "I've already gave you a proposition.";
                        };

                        message.editMessage(content).queue();
                    }
                }
        );
    }

    private void onChallengeAccepted(MessageReactionAddEvent event) {
        AcceptChallengeUseCase acceptChallengeUseCase = new AcceptChallengeUseCase(
                challengerRepository,
                challengeRepository,
                propositionRepository
        );

        event.retrieveMessage().queue(message -> {
            System.out.println(message.getContentRaw());

            String emoteId = String.format(":%s:%s", event.getReactionEmote().getName(), event.getReactionEmote().getId());
            GodData godData = godsDataProvider.findByEmote(emoteId);
            if (godData == null) {
                System.out.println("God not found: " + emoteId);
                return;
            }
            AcceptChallengeUseCase.Result result =
                    acceptChallengeUseCase.execute(event.getUserIdLong(), godData.getId());

            String content;

            if (result.getError() == null) {
                content = String.format(
                        "%s, Your starting god is `%s`! Good luck!",
                        event.getUser().getAsMention(), godData.getName()
                );
                message.editMessage(content).queue();

                message.clearReactions().queue();
            }
        });
    }

    private static ChallengerRepository createChallengerRepositoryStub() {
        return new ChallengerRepository() {
            private final Map<Long, Challenger> data = new HashMap<>();

            @Override
            public Challenger findByDiscordId(long id) {
                return data.get(id);
            }

            @Override
            public void insert(long discordId, String inGameName) {
                data.put(discordId, new Challenger(String.valueOf(discordId), inGameName, discordId));
            }
        };
    }

    private static ChallengeRepository createChallengeRepositoryStub() {
        return new ChallengeRepository() {
            private final Map<String, Challenge> data = new HashMap<>();

            @Override
            public Challenge findByChallengerId(String id) {
                return data.get(id);
            }

            @Override
            public void insert(String challengerId, Map<Integer, Integer> availableGods) {
                data.put(challengerId, new Challenge(new HashMap<>(availableGods)));
            }

            @Override
            public void update(String challengerId, Challenge newChallenge) {
                data.put(challengerId, newChallenge);
            }
        };
    }

    private static GameRulesProvider createGameRulesProviderStub() {
        return new GameRulesProvider() {
            @Override
            public int getGodsCount() {
                return 4;
            }

            @Override
            public int getChallengeProposedGodsCount() {
                return 3;
            }

            @Override
            public int getBaseRerolls() {
                return 0;
            }
        };
    }

    private PropositionRepository createPropositionRepositoryStub() {
        return new PropositionRepository() {
            private final Map<String, Proposition> data = new HashMap<>();

            @Override
            public Proposition findByChallengerId(String id) {
                return data.get(id);
            }

            @Override
            public void insert(String challengerId, int[] gods, int rerolls, long messageId) {
                data.put(challengerId, new Proposition(challengerId, gods, rerolls, messageId));
            }
        };
    }

    private GodsDataProvider createGodsDataProviderStub() {
        return new GodsDataProvider() {
            private final Map<Integer, GodData> data = Map.ofEntries(
                    Map.entry(0, new GodData(0, ":ra:727776401092116551", "Ra")),
                    Map.entry(1, new GodData(1, ":neith:727846105047629835", "Neith")),
                    Map.entry(2, new GodData(2, ":ymir:727846364020604938", "Ymir")),
                    Map.entry(3, new GodData(3, ":guanyu:727846363420819538", "Guan Yu"))
            );
            @Override
            public GodData findById(int id) {
                return data.get(id);
            }

            @Override
            public GodData findByEmote(String emoteId) {
                Optional<GodData> match =
                        data.values().stream().filter(godData -> godData.getEmoteId().equals(emoteId)).findFirst();

                return match.orElse(null);
            }
        };
    }
}
