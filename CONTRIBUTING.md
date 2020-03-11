# Welcome to redis4cats!

We follow the standard GitHub [fork & pull](https://help.github.com/articles/using-pull-requests/#fork--pull) approach to pull requests. Just fork the official repo, develop in a branch, and submit a PR!

You're always welcome to submit your PR straight away and start the discussion (without reading the rest of this wonderful doc, or the [`README.md`](README.md)). The goal of these notes is to make your experience contributing to redis4cats as smooth and pleasant as possible. We're happy to guide you through the process once you've submitted your PR.

## Ticket Guidelines

- **Bugs**:
	- Contain steps to reproduce.
	- Contain a code sample that exhibits the error.
	- Inform us if the issue is blocking you with no visible workaround
		- This will give the ticket priority
- **Features**
	- Show a code example of how that feature would be used.

## Contribution Guidelines

- **All code PRs should**:
	- have a meaningful commit message description
	- comment important things
	- include unit tests (positive and negative)
	- pass [CI](https://app.codeship.com/projects/223399), which automatically runs when your pull request is submitted.
- **Be prepared to discuss/argue-for your changes if you want them merged**!
  You will probably need to refactor so your changes fit into the larger
  codebase
- **If your code is hard to unit test, and you don't want to unit test it,
  that's ok**. But be prepared to argue why that's the case!
- **It's entirely possible your changes won't be merged**, or will get ripped
  out later. This is also the case for maintainer changes!
- **Even a rejected/reverted PR is valuable**! It helps explore the solution
  space, and know what works and what doesn't. For every line in the repo, at
  least three lines were tried, committed, and reverted/refactored, and more
  than 10 were tried without committing.
- **Feel free to send Proof-Of-Concept PRs** that you don't intend to get merged.

In case of any questions, don't hesitate to ask on our [gitter channel](https://gitter.im/profunktor-dev/redis4cats).

(these guidelines have been adapted from https://github.com/scalameta/scalameta/blob/master/CONTRIBUTING.md)
