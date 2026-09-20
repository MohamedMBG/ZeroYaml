/**
 * Adds a GitHub issue to the ZeroYAML engineering project (if not already
 * present) and sets its Status field to the given single-select option.
 *
 * Idempotent: addProjectV2ItemById returns the existing item when the issue
 * is already on the board, so re-running an event (e.g. a re-assignment)
 * only updates the Status field.
 */
async function addIssueToProjectStatus({ github, core, contentNodeId, projectId, statusFieldId, statusOptionId }) {
  const addResult = await github.graphql(
    `mutation($projectId: ID!, $contentId: ID!) {
      addProjectV2ItemById(input: { projectId: $projectId, contentId: $contentId }) {
        item { id }
      }
    }`,
    { projectId, contentId: contentNodeId }
  );

  const itemId = addResult.addProjectV2ItemById.item.id;

  await github.graphql(
    `mutation($projectId: ID!, $itemId: ID!, $fieldId: ID!, $optionId: String!) {
      updateProjectV2ItemFieldValue(
        input: {
          projectId: $projectId
          itemId: $itemId
          fieldId: $fieldId
          value: { singleSelectOptionId: $optionId }
        }
      ) {
        projectV2Item { id }
      }
    }`,
    { projectId, itemId, fieldId: statusFieldId, optionId: statusOptionId }
  );

  core.info(`Set project item ${itemId} status field ${statusFieldId} to option ${statusOptionId}.`);
}

module.exports = { addIssueToProjectStatus };
