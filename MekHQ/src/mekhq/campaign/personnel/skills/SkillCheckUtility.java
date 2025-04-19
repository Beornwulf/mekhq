/*
 * Copyright (C) 2025 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MekHQ.
 *
 * MekHQ is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MekHQ is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 */
package mekhq.campaign.personnel.skills;

import static java.lang.Math.floor;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static megamek.common.Compute.d6;
import static mekhq.campaign.personnel.enums.GenderDescriptors.HIS_HER_THEIR;
import static mekhq.campaign.personnel.skills.enums.MarginOfSuccess.BARELY_MADE_IT;
import static mekhq.campaign.personnel.skills.enums.MarginOfSuccess.DISASTROUS;
import static mekhq.campaign.personnel.skills.enums.MarginOfSuccess.getMarginOfSuccessObjectFromMarginValue;
import static mekhq.campaign.personnel.skills.enums.MarginOfSuccess.getMarginOfSuccessString;
import static mekhq.campaign.personnel.skills.enums.MarginOfSuccess.getMarginValue;
import static mekhq.utilities.MHQInternationalization.getFormattedTextAt;
import static mekhq.utilities.ReportingUtilities.CLOSING_SPAN_TAG;
import static mekhq.utilities.ReportingUtilities.spanOpeningWithCustomColor;

import java.util.List;

import megamek.common.TargetRoll;
import megamek.logging.MMLogger;
import mekhq.MekHQ;
import mekhq.campaign.event.PersonChangedEvent;
import mekhq.campaign.personnel.Person;
import mekhq.campaign.personnel.skills.enums.MarginOfSuccess;
import mekhq.campaign.personnel.skills.enums.SkillAttribute;

/**
 * This class calculates the target number for a skill check based on the person's attributes, skills, and the
 * associated skill type. It determines if the skill check succeeds or fails by rolling dice and calculates the
 * resulting margin of success and corresponding text description.
 *
 * @author Illiani
 * @since 0.50.05
 */
public class SkillCheckUtility {
    private static final MMLogger logger = MMLogger.create(SkillCheckUtility.class);
    private static final String RESOURCE_BUNDLE = "mekhq.resources." + SkillCheckUtility.class.getSimpleName();

    /**
     * The target number for an untrained skill check with one linked attribute.
     */
    protected static final int UNTRAINED_TARGET_NUMBER_ONE_LINKED_ATTRIBUTE = 12; // ATOW pg 43

    /**
     * The target number for an untrained skill check with two linked attributes.
     */
    protected static final int UNTRAINED_TARGET_NUMBER_TWO_LINKED_ATTRIBUTES = 18; // ATOW pg 43

    /**
     * The penalty for attempting a skill check with an untrained skill.
     */
    protected static final int UNTRAINED_SKILL_MODIFIER = 4; // ATOW pg 43

    private final Person person;
    private final String skillName;
    private int marginOfSuccess;
    private String resultsText;
    private TargetRoll targetNumber;
    boolean isCountUp;
    private int roll;
    private boolean usedEdge;

    /**
     * Performs a skill check for the given person and skill.
     *
     * <p>This constructor performs a skill check by rolling dice and determining the outcome based on the person's
     * target number and an optional use of edge.</p>
     *
     * <p><b>Usage:</b> This constructor gives you a lot of control over what information you need, but for most
     * use-cases you can get away with using the lazy method: {@link #performQuickSkillCheck(Person, String, int)}.</p>
     *
     * @param person       the {@link Person} performing the skill check
     * @param skillName    the name of the skill being used
     * @param miscModifier any special modifiers, as an {@link Integer}. These values are subtracted from the target
     *                     number, if the associated skill is classified as 'count up', otherwise they are added to the
     *                     target number. This means negative values are bonuses, positive values are penalties.
     * @param useEdge      whether the person should use edge for a re-roll if the first attempt fails
     *
     * @author Illiani
     * @since 0.50.05
     */
    public SkillCheckUtility(final Person person, final String skillName, final int miscModifier,
          final boolean useEdge) {
        this.person = person;
        this.skillName = skillName;

        if (isPersonNull()) {
            return;
        }

        final SkillType skillType = SkillType.getType(skillName);
        isCountUp = skillType.isCountUp();
        targetNumber = determineTargetNumber(person, skillType, miscModifier);
        performCheck(useEdge);
    }

    /**
     * Performs a quick skill check for a person based on the specified skill name.
     *
     * <p>This method creates a {@link SkillCheckUtility} instance to evaluate whether the given person is successful
     * in performing the specified skill.</p>
     *
     * <p><b>Usage:</b> This is a nice, quick lazy method for performing a skill check. For most use-cases across
     * MekHQ this is the method you want to use. If you need more control use the class constructor, instead.</p>
     *
     * @param person       the {@link Person} performing the skill check
     * @param skillName    the name of the skill being checked
     * @param miscModifier any special modifiers, as an {@link Integer}. These values are subtracted from the target
     *                     number, if the associated skill is classified as 'count up', otherwise they are added to the
     *                     target number. This means negative values are bonuses, positive values are penalties.
     *
     * @return {@code true} if the skill check is successful, {@code false} otherwise
     *
     * @author Illiani
     * @since 0.50.05
     */
    public static boolean performQuickSkillCheck(final Person person, final String skillName, final int miscModifier) {
        SkillCheckUtility skillCheck = new SkillCheckUtility(person, skillName, miscModifier, false);
        return skillCheck.isSuccess();
    }

    /**
     * Checks if the {@code person} object is {@code null} and handles the null case by auto-failing the check with
     * obviously wrong results.
     *
     * <p>If the {@code person} is {@code null}, the method logs a debug message, sets a {@code DISASTROUS} failure
     * margin, and assigns out-of-range values to the {@code targetNumber} and {@code roll} to make the issue easily
     * identifiable.</p>
     *
     * @return {@code true} if the {@code person} is {@code null}, {@code false} otherwise.
     *
     * @author Illiani
     * @since 0.50.05
     */
    private boolean isPersonNull() {
        if (person == null) {
            logger.debug("Null person passed into SkillCheckUtility." +
                               " Auto-failing check with bogus results so the bug stands out.");

            marginOfSuccess = getMarginValue(DISASTROUS);
            resultsText = getFormattedTextAt(RESOURCE_BUNDLE, "skillCheck.nullPerson");
            targetNumber = new TargetRoll(Integer.MAX_VALUE, "ERROR");
            roll = Integer.MIN_VALUE;
            return true;
        }

        return false;
    }

    /**
     * Generates a formatted results text that provides details about the outcome of a skill check.
     *
     * <p>This method creates a descriptive summary of the skill check results, including the person's title, name,
     * gender-based pronoun, skill name, roll, target number, margin of success, and edge usage (if any). The text is
     * color-coded based on the margin of success to visually indicate the outcome:</p>
     *
     * <ul>
     *   <li><b>Neutral Margin:</b> Warning color (e.g., yellow).</li>
     *   <li><b>Failure:</b> Negative color (e.g., red).</li>
     *   <li><b>Success:</b> Positive color (e.g., green).</li>
     * </ul>
     *
     * <p>If edge is used for a reroll, the results will also include a note about the reroll action.</p>
     *
     * <p>If the skill name is {@code null}, this method returns a localized error message related to skill name resolution,
     * indicating that an error occurred during the skill check results generation.</p>
     *
     * @return a formatted string representing the results of the skill check. This string includes structured
     *       information about the person, the skill, numerical results, edge usage, or an error message if the skill
     *       name is {@code null}.
     *
     * @author Illiani
     * @since 0.50.05
     */
    private String generateResultsText() {
        if (skillName == null) {
            return getFormattedTextAt(RESOURCE_BUNDLE, "skillCheck.nullSkillName");
        }

        String fullTitle = person.getHyperlinkedFullTitle();
        String firstName = person.getFirstName();
        String genderedReferenced = HIS_HER_THEIR.getDescriptor(person.getGender());

        String colorOpen;
        int neutralMarginValue = getMarginValue(BARELY_MADE_IT);
        if (marginOfSuccess == neutralMarginValue) {
            colorOpen = spanOpeningWithCustomColor(MekHQ.getMHQOptions().getFontColorWarningHexColor());
        } else if (marginOfSuccess < neutralMarginValue) {
            colorOpen = spanOpeningWithCustomColor(MekHQ.getMHQOptions().getFontColorNegativeHexColor());
        } else {
            colorOpen = spanOpeningWithCustomColor(MekHQ.getMHQOptions().getFontColorPositiveHexColor());
        }
        String status = getFormattedTextAt(RESOURCE_BUNDLE,
              "skillCheck.results." + (isSuccess() ? "success" : "failure"));
        String mainMessage = getFormattedTextAt(RESOURCE_BUNDLE,
              "skillCheck.results",
              fullTitle,
              colorOpen,
              status,
              CLOSING_SPAN_TAG,
              genderedReferenced,
              skillName,
              roll,
              targetNumber);

        String edgeUseText = !usedEdge ? "" : getFormattedTextAt(RESOURCE_BUNDLE, "skillCheck.rerolled", firstName);

        if (!edgeUseText.isBlank()) {
            mainMessage = mainMessage + "<p>" + edgeUseText + "</p>";
        }

        MarginOfSuccess marginOfSuccessObject = getMarginOfSuccessObjectFromMarginValue(marginOfSuccess);
        String marginOfSuccessText = getMarginOfSuccessString(marginOfSuccessObject);

        return mainMessage + "<p>" + marginOfSuccessText + "</p>";
    }

    /**
     * Gets the calculated margin of success for this skill check.
     *
     * <p>The margin of success represents how much better (or worse) the roll was compared to the target number.</p>
     *
     * <p><b>Usage:</b> You want to call this method whenever you care about how well a check was passed. Or how
     * badly it was failed. If you only care whether the check was passed or failed use {@link #isSuccess()}
     * instead.</p>
     *
     * @return the margin of success
     *
     * @author Illiani
     * @since 0.50.05
     */
    public int getMarginOfSuccess() {
        return marginOfSuccess;
    }

    /**
     * Determines whether the skill check was successful.
     *
     * <p>A skill check is considered successful if the calculated margin of success is greater than or equal to the
     * margin value of {@link MarginOfSuccess#BARELY_MADE_IT}.</p>
     *
     * <p><b>Usage:</b> You want to call this method whenever you only care whether the check was passed or failed.
     * If you want to know how well the character did use {@link #getMarginOfSuccess()} instead.</p>
     *
     * @return {@code true} if the skill check succeeded, {@code false} otherwise
     *
     * @author Illiani
     * @since 0.50.05
     */
    public boolean isSuccess() {
        return marginOfSuccess >= getMarginValue(BARELY_MADE_IT);
    }

    /**
     * Gets the results text for the margin of success.
     *
     * <p>This is a descriptive string representing the outcome of the skill check, based on the calculated margin of
     * success.</p>
     *
     * @return the results text for the skill check
     *
     * @author Illiani
     * @since 0.50.05
     */
    public String getResultsText() {
        return resultsText;
    }

    /**
     * Gets the target number for the skill check.
     *
     * <p>The target number represents the value that the rolled number must meet or exceed for the skill check to
     * succeed.</p>
     *
     * @return the target number for the skill check
     *
     * @author Illiani
     * @since 0.50.05
     */
    public TargetRoll getTargetNumber() {
        return targetNumber;
    }

    /**
     * Gets the roll result for the skill check.
     *
     * <p>The roll is the result of the dice roll used to determine whether the skill check succeeded or failed.</p>
     *
     * @return the roll result for the skill check
     *
     * @author Illiani
     * @since 0.50.05
     */
    public int getRoll() {
        return roll;
    }

    /**
     * Checks whether edge was used during the skill check.
     *
     * <p>Edge provides the opportunity to re-roll if the initial skill check fails, allowing a chance to improve the
     * outcome.</p>
     *
     * @return {@code true} if edge was used during the skill check, {@code false} otherwise
     *
     * @author Illiani
     * @since 0.50.05
     */
    public boolean isUsedEdge() {
        return usedEdge;
    }

    /**
     * Determines the target number for a skill check based on the person's attributes, skill type, and whether they are
     * trained in the skill.
     *
     * <p>If the person is untrained, the target number is based on constants for untrained rolls and the number of
     * linked attributes. Otherwise, it is based on the final skill value and attribute modifiers.</p>
     *
     * @param person       the {@link Person} performing the skill check
     * @param skillType    the associated {@link SkillType} for the {@link Skill} being used.
     * @param miscModifier any special modifiers, as an {@link Integer}. These values are subtracted from the target
     *                     number, if the associated skill is classified as 'count up', otherwise they are added to the
     *                     target number. This means negative values are bonuses, positive values are penalties.
     *
     * @return the target number for the skill check
     *
     * @author Illiani
     * @since 0.50.05
     */
    public static TargetRoll determineTargetNumber(Person person, SkillType skillType, int miscModifier) {
        final String skillName = skillType.getName();
        final Attributes characterAttributes = person.getATOWAttributes();

        boolean isUntrained = !person.hasSkill(skillName);
        int linkedAttributeCount = skillType.getLinkedAttributeCount();

        TargetRoll targetNumber = new TargetRoll();

        if (isUntrained) {
            if (linkedAttributeCount > 1) {
                targetNumber.addModifier(UNTRAINED_TARGET_NUMBER_TWO_LINKED_ATTRIBUTES,
                      getFormattedTextAt(RESOURCE_BUNDLE, "skillCheck.untrained.twoLinkedAttributes"));
            } else {
                targetNumber.addModifier(UNTRAINED_TARGET_NUMBER_ONE_LINKED_ATTRIBUTE,
                      getFormattedTextAt(RESOURCE_BUNDLE, "skillCheck.untrained.oneLinkedAttribute"));
            }

            getTotalAttributeScoreForSkill(targetNumber, characterAttributes, skillType);

            targetNumber.addModifier(UNTRAINED_SKILL_MODIFIER, getFormattedTextAt(RESOURCE_BUNDLE, "skillCheck.untrained.skill"));
        } else {
            Skill skill = person.getSkill(skillName);
            int skillValue = skill.getFinalSkillValue(person.getOptions(), person.getReputation());
            targetNumber.addModifier(skillValue, skillName);
            getTotalAttributeModifier(targetNumber, characterAttributes, skillType);
        }

        if (skillType.isCountUp()) {
            targetNumber.addModifier(-miscModifier, getFormattedTextAt(RESOURCE_BUNDLE, "skillCheck.miscModifier"));
        } else {
            targetNumber.addModifier(miscModifier, getFormattedTextAt(RESOURCE_BUNDLE, "skillCheck.miscModifier"));
        }

        return targetNumber;
    }

    /**
     * Performs a skill check for a given person, determining success or failure based on dice rolls and optionally
     * modifying the results by using edge points for a re-roll.
     *
     * <p>This method initiates a die roll to compare against a pre-determined target number. If the initial roll
     * succeeds, the results are calculated and stored. If the initial roll fails and edge usage is allowed and
     * available, the method consumes one edge point from the person and performs a re-roll. The final results include
     * the margin of success and accompanying descriptive result text, both of which are stored internally.
     *
     * @param useEdge whether the person should use an edge point to perform a re-roll if the initial roll fails. If
     *                {@code true}, edge use will be attempted, subject to availability.
     *
     * @author Illiani
     * @since 0.50.05
     */
    void performCheck(boolean useEdge) {
        roll = d6(2);
        if (performInitialRoll(useEdge)) {
            return;
        }

        roll = d6(2);
        rollWithEdge();
    }

    /**
     * Handles the logic for the initial dice roll in the skill check, determining whether the roll succeeds or if edge
     * usage is necessary.
     *
     * <p>This method evaluates the result of the first roll against the target number and determines if further
     * action (re-roll with edge) is needed. If the roll meets or exceeds the target number, or if edge usage is
     * disallowed or unavailable, the results are finalized based on the initial roll.
     *
     * @param useEdge whether the person is allowed to use edge for a re-roll if the initial roll fails.
     *
     * @return {@code true} if the skill check is resolved (initial roll succeeds or no edge usage is possible);
     *       {@code false} if further action (re-roll using edge) is required.
     *
     * @author Illiani
     * @since 0.50.05
     */
    boolean performInitialRoll(boolean useEdge) {
        int availableEdge = person.getCurrentEdge();
        int targetNumberValue = targetNumber.getValue();

        if (roll >= targetNumberValue || !useEdge || availableEdge < 1) {
            int difference = isCountUp ? targetNumberValue - roll : roll - targetNumberValue;

            logger.info(getFormattedTextAt(RESOURCE_BUNDLE, "skillCheck.report"),
                  skillName,
                  roll,
                  targetNumber,
                  difference);

            marginOfSuccess = MarginOfSuccess.getMarginOfSuccess(difference);
            resultsText = generateResultsText();
            return true;
        }
        return false;
    }

    /**
     * Performs the re-roll logic for a skill check when edge is used, decrementing the person's edge points and
     * calculating the results based on the new roll.
     *
     * <p>This method is invoked only when the initial roll fails, and edge usage is allowed and available. It
     * handles decrementing the person's edge points by 1, marks that edge was used in the operation, and calculates the
     * new margin of success and results text based on the re-roll.
     *
     * @author Illiani
     * @since 0.50.05
     */
    private void rollWithEdge() {
        person.changeCurrentEdge(-1);
        MekHQ.triggerEvent(new PersonChangedEvent(person));
        usedEdge = true;

        int targetNumberValue = targetNumber.getValue();

        int difference = isCountUp ? targetNumberValue - roll : roll - targetNumberValue;
        marginOfSuccess = MarginOfSuccess.getMarginOfSuccess(difference);
        resultsText = generateResultsText();
    }

    /**
     * Calculates the total attribute modifier for a given skill type based on the character's attributes
     * and applies the modifiers to the target roll.
     *
     * <p>This method retrieves the attributes linked to the specified {@link SkillType} and calculates
     * the total contribution of their modifiers to the target roll. Each attribute's score is converted
     * into an individual modifier using {@link #getIndividualAttributeModifier(int)}, and the modifier is
     * then added to both:</p>
     *
     * <ul>
     *   <li>The total attribute modifier (returned by the method), and</li>
     *   <li>The {@link TargetRoll}, where the attribute modifier is applied as a negative value.</li>
     * </ul>
     *
     * <p>Attributes that are set to {@link SkillAttribute#NONE} are ignored during this process.</p>
     *
     * <p>The calculated attribute modifiers are applied directly to the {@link TargetRoll} using
     * {@link TargetRoll#addModifier(int, String)}, where the negative modifier is associated with the
     * attribute's label.</p>
     *
     * @param targetNumber         the {@link TargetRoll} representing the current target number,
     *                             which will be adjusted based on the character's attribute modifiers
     * @param characterAttributes  the {@link Attributes} object representing the character's
     *                             raw attribute scores that determine the skill check modifiers
     * @param skillType            the {@link SkillType} being assessed, whose linked attributes
     *                             contribute to the total modifier calculation
     *
     * @return the total attribute modifier calculated for the given skill type, which is the sum
     *         of the individual modifiers for each linked attribute. If any of the parameters are {@code null}
     *         returns 0.
     *
     * @author Illiani
     * @since 0.50.05
     */
    public static int getTotalAttributeModifier(TargetRoll targetNumber, final Attributes characterAttributes, final SkillType skillType) {
        if (targetNumber == null || characterAttributes == null || skillType == null) {
            return 0;
        }

        List<SkillAttribute> linkedAttributes = List.of(skillType.getFirstAttribute(), skillType.getSecondAttribute());

        int totalModifier = 0;
        for (SkillAttribute attribute : linkedAttributes) {
            if (attribute == SkillAttribute.NONE) {
                continue;
            }

            int attributeScore = characterAttributes.getAttribute(attribute);
            int attributeModifier = getIndividualAttributeModifier(attributeScore);
            totalModifier += attributeModifier;
            targetNumber.addModifier(-attributeModifier, attribute.getLabel());
        }

        return totalModifier;
    }

    /**
     * Calculates the individual attribute modifier for a given attribute score.
     *
     * <p>The modification is based on a predefined scale, with higher scores providing positive modifiers and lower
     * scores providing negative modifiers.</p>
     *
     * @param attributeScore the score of the attribute
     *
     * @return the attribute modifier for the given score
     *
     * @author Illiani
     * @since 0.50.05
     */
    public static int getIndividualAttributeModifier(int attributeScore) {
        int actualScore = max(attributeScore, 0);

        return switch (actualScore) { // ATOW pg 41
            case 0 -> -4;
            case 1 -> -2;
            case 2, 3 -> -1;
            case 4, 5, 6 -> 0;
            case 7, 8, 9 -> 1;
            case 10 -> 2;
            default -> min(5, (int) floor((double) actualScore / 3));
        };
    }

    /**
     * Applies attribute-based modifiers to a target roll and calculates the total attribute score
     * for a given skill.
     *
     * <p>This method retrieves the attributes linked to a specified {@link SkillType} and calculates
     * their total contribution to both:</p>
     * <ul>
     *   <li>The target roll by applying modifiers (negative of the attribute values), and</li>
     *   <li>The total attribute score, which it returns as an integer.</li>
     * </ul>
     * <p>Attributes that are set to {@link SkillAttribute#NONE} are ignored during this process.</p>
     *
     * <p>For each relevant attribute:</p>
     * <ul>
     *   <li>The method adds the negative of the attribute value as a modifier to the {@link TargetRoll}
     *       using {@link TargetRoll#addModifier(int, String)}, where the second parameter is the attribute's label.</li>
     *   <li>The total attribute score is incremented by the raw attribute value.</li>
     * </ul>
     *
     * @param targetNumber         the {@link TargetRoll} representing the current target
     *                             number, which will be adjusted based on the character's
     *                             attribute values
     * @param characterAttributes  the {@link Attributes} object representing the character's
     *                             attributes that contribute to the skill check
     * @param skillType            the {@link SkillType} being assessed, whose linked attributes
     *                             determine the modifiers to be applied
     *
     * @return the total attribute score summed from all relevant attributes linked to the skill. If any of the
     * parameters are {@code null}, the method will log an error and return {@code 0}.
     *
     * @author Illiani
     * @since 0.50.05
     */
    public static int getTotalAttributeScoreForSkill(TargetRoll targetNumber, final Attributes characterAttributes,
          final SkillType skillType) {
        // Validation
        if (targetNumber == null || characterAttributes == null || skillType == null) {
            logger.error("Null parameter passed into SkillCheckUtility.getTotalAttributeScoreForSkill." +
                               " targetNumber: {}, characterAttributes: {}, skillType: {}", targetNumber, characterAttributes,
                  skillType);
            return 0;
        }

        int totalModifier = 0;
        List<SkillAttribute> linkedAttributes = List.of(skillType.getFirstAttribute(), skillType.getSecondAttribute());

        for (SkillAttribute attribute : linkedAttributes) {
            if (attribute == SkillAttribute.NONE) {
                continue;
            }

            int attributeScore = characterAttributes.getAttribute(attribute);
            totalModifier += attributeScore;
            targetNumber.addModifier(-attributeScore, attribute.getLabel());
        }

        return totalModifier;
    }
}
