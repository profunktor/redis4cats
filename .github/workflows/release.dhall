let GithubActions =
      https://raw.githubusercontent.com/gvolpe/github-actions-dhall/feature/scala-actions/package.dhall sha256:3eec947980724a16fad54b449fddb03d51dabfed94e9982662f58249cf88255e

let setup =
      [ GithubActions.steps.checkout
      , GithubActions.steps.java-setup { java-version = "11" }
      , GithubActions.steps.gpg-setup
      , GithubActions.steps.sbt-ci-release
          { ref = "\${{ github.ref }}"
          , pgpPassphrase = "\${{ secrets.PGP_PASSPHRASE }}"
          , pgpSecret = "\${{ secrets.PGP_SECRET }}"
          , sonatypePassword = "\${{ secrets.SONATYPE_PASSWORD }}"
          , sonatypeUsername = "\${{ secrets.SONATYPE_USERNAME }}"
          }
      ]

in  GithubActions.Workflow::{
    , name = "Release"
    , on = GithubActions.On::{
      , push = Some GithubActions.Push::{
          , branches = Some [ "master" ]
          , tags = Some ["*"]
        }
      }
    , jobs = toMap
        { build = GithubActions.Job::{
          , name = "Publish"
          , needs = None (List Text)
          , runs-on = GithubActions.types.RunsOn.`ubuntu-18.04`
          , steps = setup
          }
        }
    }
