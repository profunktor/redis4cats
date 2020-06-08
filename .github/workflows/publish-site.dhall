let GithubActions =
      https://raw.githubusercontent.com/gvolpe/github-actions-dhall/steps/cachix/package.dhall sha256:33c360447910bdb69f89d794e3579dc7c77c0128d0fcddb90b4cd30b96f52648

let setup =
      [ GithubActions.steps.checkout
      , GithubActions.steps.cachix/install-nix
      , GithubActions.steps.cachix/cachix { cache-name = "scala-microsite" }
      ,   GithubActions.steps.run
            { run = "nix-shell --run \"sbt publishSite\"" }
        // { name = Some "Building microsite 🚧" }
      ]

in  GithubActions.Workflow::{
    , name = "Microsite"
    , on = GithubActions.On::{
      , push = Some GithubActions.Push::{
        , branches = Some [ "master" ]
        , paths = Some [ "site/**", "**/README.md" ]
        }
      }
    , jobs = toMap
        { publish = GithubActions.Job::{
          , needs = None (List Text)
          , runs-on = GithubActions.types.RunsOn.`ubuntu-18.04`
          , steps = setup
          , env = Some (toMap { GITHUB_TOKEN = "\${{ secrets.GITHUB_TOKEN }}" })
          }
        }
    }
