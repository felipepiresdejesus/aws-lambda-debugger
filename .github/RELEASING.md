# Release Process

This document describes how to create a new release and publish the plugin to the JetBrains Marketplace.

## Semantic Versioning

This project follows [Semantic Versioning](https://semver.org/):
- **MAJOR** version (X.0.0): Incompatible API changes
- **MINOR** version (0.X.0): New functionality in a backward-compatible manner
- **PATCH** version (0.0.X): Backward-compatible bug fixes

Pre-release versions can be tagged with suffixes like `-alpha`, `-beta`, `-rc1`, etc.

## Creating a Release

### 1. Prepare the Release

Ensure all changes are committed and pushed to the main branch:

```bash
git checkout main
git pull origin main
```

### 2. Create and Push a Tag

Create a semantic version tag (with 'v' prefix):

```bash
# For a regular release
git tag v1.0.0
git push origin v1.0.0

# For a pre-release
git tag v1.1.0-beta
git push origin v1.1.0-beta
```

### 3. Automated Deployment

Once the tag is pushed, GitHub Actions will automatically:
1. Extract the version from the tag
2. Build the plugin with the version number
3. Run verification checks
4. Publish to JetBrains Marketplace (using `JETBRAINS_TOKEN` secret)
5. Create a GitHub Release with the built artifacts

### 4. Monitor the Workflow

Watch the workflow progress at:
```
https://github.com/YOUR_USERNAME/YOUR_REPO/actions
```

## Required Secrets

Before releasing, ensure the following GitHub repository secrets are configured:

### JETBRAINS_TOKEN

Get your token from: https://plugins.jetbrains.com/author/me/tokens

Add it to your repository:
1. Go to repository Settings → Secrets and variables → Actions
2. Click "New repository secret"
3. Name: `JETBRAINS_TOKEN`
4. Value: Your JetBrains Marketplace token

## Example Release Workflow

```bash
# Update changelog (if applicable)
vim CHANGELOG.md

# Commit changes
git add .
git commit -m "Prepare release v1.0.0"
git push origin main

# Create and push tag
git tag v1.0.0
git push origin v1.0.0

# Wait for automated deployment
# Check: https://github.com/YOUR_USERNAME/YOUR_REPO/actions
```

## Version Format Examples

Valid tag formats that trigger deployment:
- `v1.0.0` - Major release
- `v1.2.3` - Standard release
- `v2.0.0-alpha` - Alpha pre-release
- `v1.5.0-beta.1` - Beta pre-release with iteration
- `v1.0.0-rc1` - Release candidate

## Troubleshooting

### Build Fails
- Check the Actions log for detailed error messages
- Ensure all tests pass locally: `./gradlew test`
- Verify the plugin builds locally: `./gradlew buildPlugin`

### Publication Fails
- Verify `JETBRAINS_TOKEN` is correctly set in repository secrets
- Check token permissions on JetBrains Marketplace
- Ensure plugin ID and metadata are correct in `plugin.xml`

### Wrong Version Published
- Delete the tag: `git tag -d v1.0.0 && git push origin :refs/tags/v1.0.0`
- Fix the issue
- Create a new tag with a bumped version
