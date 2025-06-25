/*
 * Copyright 2024-2025 Embabel Software, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.template.agent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.domain.library.HasContent;
import com.embabel.agent.prompt.persona.Persona;
import com.embabel.common.ai.model.AutoModelSelectionCriteria;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.common.ai.prompt.PromptContributionLocation;
import com.embabel.common.core.types.Timestamped;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.NonNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;


abstract class Personas {
    static final Persona WRITER = Persona.create(
            "Roald Dahl",
            "A creative storyteller who loves to weave imaginative tales that are a bit unconventional",
            "Quirky",
            "Create memorable stories that captivate the reader's imagination.",
            "",
            PromptContributionLocation.BEGINNING
    );
    static final Persona REVIEWER = Persona.create(
            "Media Book Review",
            "New York Times Book Reviewer",
            "Professional and insightful",
            "Help guide readers toward good stories",
            "",
            PromptContributionLocation.BEGINNING
    );
}

record Story(String text) {}

record ReviewedStory(
        Story story,
        String review,
        Persona reviewer
) implements HasContent, Timestamped {

    @Override
    @NonNull
    public Instant getTimestamp() {
        return Instant.now();
    }

    @Override
    @NonNull
    public String getContent() {
        return String.format("""
            # Story
            %s

            # Review
            %s

            # Reviewer
            %s, %s
            """,
                story.text(),
                review,
                reviewer.getName(),
                getTimestamp().atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("EEEE, MMMM dd, yyyy"))
        ).trim();
    }
}

@Agent(description = "Generate a story based on user input and review it")
@Profile("!test")
class WriteAndReviewAgent {

    private final int storyWordCount;
    private final int reviewWordCount;

    WriteAndReviewAgent(
            @Value("${storyWordCount:100}") int storyWordCount,
            @Value("${reviewWordCount:100}") int reviewWordCount
    ) {
        this.storyWordCount = storyWordCount;
        this.reviewWordCount = reviewWordCount;
    }

    @Action
    Story craftStory(UserInput userInput) {
        return PromptRunner.usingLlm(
                 LlmOptions.fromCriteria(AutoModelSelectionCriteria.INSTANCE)
                        .withTemperature(0.9) // Higher temperature for more creative output
        ).withPromptContributor(Personas.WRITER)
                .createObject(String.format("""
                Craft a short story in %d words or less.
                The story should be engaging and imaginative.
                Use the user's input as inspiration if possible.
                If the user has provided a name, include it in the story.

                # User input
                %s
                """,
                        storyWordCount,
                        userInput.getContent()
                ).trim(), Story.class);
    }

    @AchievesGoal(description="The story has been crafted and reviewed by a book reviewer")
    @Action
    ReviewedStory reviewStory(UserInput userInput, Story story, OperationContext context) {
        String review = context.promptRunner()
                .withLlm(LlmOptions.fromCriteria(AutoModelSelectionCriteria.INSTANCE))
                .withPromptContributor(Personas.REVIEWER)
                .generateText(String.format("""
                You will be given a short story to review.
                Review it in %d words or less.
                Consider whether or not the story is engaging, imaginative, and well-written.
                Also consider whether the story is appropriate given the original user input.

                # Story
                %s

                # User input that inspired the story
                %s
                """,
                        reviewWordCount,
                        story.text(),
                        userInput.getContent()
                ).trim());

        return new ReviewedStory(
                story,
                review,
                Personas.REVIEWER
        );
    }
}