provider "aws" {
  region = "us-east-1"
  profile = "default"
  shared_credentials_file = "~/.aws/credentials"
  max_retries = 3
}

resource "aws_cognito_user_pool" "pool" {
  name = "standardUserPool"
  email_configuration {
    email_sending_account = "COGNITO_DEFAULT"
  }
  
  verification_message_template {
    email_message = "please verify with {####}"
    email_subject = "Garden Share Verification"
    email_message_by_link = "click here to verify {##Click Here##}"
    default_email_option = "CONFIRM_WITH_LINK"
  }
  auto_verified_attributes = ["email"]
  username_attributes = ["email"]
}

resource "aws_cognito_user_pool_client" "client" {
     name = "client"
     user_pool_id = "${aws_cognito_user_pool.pool.id}"
     explicit_auth_flows = ["ADMIN_NO_SRP_AUTH"]
}

resource "aws_cognito_user_group" "main" {
  name         = "Sellers"
  user_pool_id = aws_cognito_user_pool.pool.id
  description  = "Managed by Terraform"
}

resource "aws_cognito_user_pool_domain" "main" {
  domain       = "gardenshare"
  user_pool_id = aws_cognito_user_pool.pool.id
}

output "ClientID" {
  value = aws_cognito_user_pool_client.client.id
}


