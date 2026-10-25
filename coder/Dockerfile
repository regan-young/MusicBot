# The coder sidecar (see server.mjs): Claude Code, plus the JDK and Maven to
# build and test the bot, plus git and ssh to push.
FROM node:22-bookworm-slim AS node

FROM maven:3.9-eclipse-temurin-25
RUN apt-get update \
    && apt-get install -y --no-install-recommends git openssh-client ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && npm install -g @anthropic-ai/claude-code@2.1.296 \
    && npm cache clean --force
# GitHub's host keys, so the first push doesn't stop at a prompt
RUN ssh-keyscan -t ed25519,rsa github.com > /etc/ssh/ssh_known_hosts
# the image sets MAVEN_CONFIG to /root/.m2, which the bot's uid can't use;
# HOME (a volume) holds ~/.m2, ~/.ssh and ~/.claude. ssh looks up the home
# directory in /etc/passwd (uid 1000 is "ubuntu" there), not $HOME, so it
# gets the key path explicitly.
ENV MAVEN_CONFIG="" HOME=/home/coder DISABLE_AUTOUPDATER=1 \
    GIT_SSH_COMMAND="ssh -i /home/coder/.ssh/id_ed25519 -o IdentitiesOnly=yes"
COPY server.mjs /opt/coder/server.mjs
WORKDIR /work
# the maven image's entrypoint copies settings into MAVEN_CONFIG; not needed
ENTRYPOINT []
CMD ["node", "/opt/coder/server.mjs"]
