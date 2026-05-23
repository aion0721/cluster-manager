# cluster-manager Development Notes

## Project Overview

This repository is the backend for `cluster-manager`.

- Use Java + Quarkus + Fabric8 Kubernetes Client.
- Provide REST APIs for a React frontend.
- Manage k3s first, with future OpenShift support in mind.
- Keep OpenShift-specific behavior isolated so it can be split into a separate implementation later.

## Kubernetes Access Policy

- Kubernetes operations must use Fabric8 Kubernetes Client.
- Do not execute `kubectl` commands from the backend.
- Do not implement `port-forward` in the backend.
- A future API may return a `kubectl port-forward` command for users to run on their own PCs.

## Data Policy

- Do not use a database.
- Manage user information with Kubernetes Namespace labels and annotations.
- Do not store ServiceAccount tokens long term.

## Naming Rules

- Create one Namespace per user.
- Namespace name: `dev-{userId}`.
- ServiceAccount name inside the Namespace: `dev-user`.
- DevContainer Deployment name: `devcontainer`.
- DevContainer Service name: `devcontainer`.

## Configuration

Cluster-manager behavior should be driven by `application.properties` where practical.
Avoid hard-coding cluster-manager labels, names, images, and prefixes in business logic.

## Scope Notes

- The first implementation scope is Kubernetes connectivity checks and project policy setup.
- User creation APIs are intentionally out of scope for the initial foundation.
