let GithubActions =
      https://raw.githubusercontent.com/gvolpe/github-actions-dhall/feature/scala-actions/package.dhall sha256:c9b9020c54478267364393db3f735974ec4b6eafa4c88057912f77dc9dcae7be

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
