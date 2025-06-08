# FireMUD Design Overview

# Table of Contents

# Introduction

FireMUD is a MUD platform engine including Web GUI tooling, to allow creators to easily design and deploy their game ideas.

This Design Overview document will detail the overall design strategy and documentation decisions of the project.

This document currently includes:

- Design investigation to discover documentation types

- Discussion of benefits of different types of documentation

- Selection choices and reasoning for the various documentation types

# Assumptions

Link to info:

# Design Research

## Types of Documentation

There are several types of documents that are commonly used in software projects, and the specific documents that are necessary will depend on the size and complexity of the project.

Overall, good design documents should provide a clear and detailed specification of the software system, and should be easy to understand and maintain. They should also be flexible enough to accommodate changes and updates to the system throughout the development process.

### Requirements Documentation

These documents outline the functional and non-functional requirements of the software system. It should describe the features and capabilities of the system, as well as any constraints or limitations that must be taken into consideration. It helps ensure that all stakeholders have a common understanding of what the software should do and meet needs.

#### Business Requirements Documentation (BRD)

This documentation describes the business objectives, goals, and high-level requirements for the software. It provides an overview of the project and helps to ensure that everyone involved in the project understands what the software is intended to achieve.

#### Functional Requirements Documentation (FRD)

This documentation provides a detailed description of the functional requirements of the software. It specifies what the software should do in terms of its features, functions, and capabilities. The FRD is typically created from the requirements captured in the BRD.

#### Non-Functional Requirements Documentation

This documentation specifies the non-functional requirements of the software, such as performance, reliability, usability, and security. It describes the criteria that the software must meet to be considered acceptable.

#### Use Case Document

Use case documentation describe how a user interacts with the software in a specific scenario to achieve a goal. They typically include descriptions of the actors involved in the scenario, the steps that the user takes to achieve their goal, and the expected outcomes.

#### User Stories

User stories are brief descriptions of a feature or requirement from the perspective of the user. They are a way to capture the functionality of the system in a way that is easy to understand and communicate. User stories can be used to help prioritise features and to guide development efforts.

#### Traceability Matrix

This documentation shows the relationship between the requirements and other project artefacts, such as design documents, test plans, and code. It helps to ensure that all the requirements are covered and that changes to one requirement are properly reflected in other project artefacts.

### Architecture Design Documentation

These documents describe the high-level architecture of the software system, including its components, modules, their interactions, and any design patterns being used. It should include a description of how the system will be deployed and integrated with other systems.

#### Architecture Design Document

This is the primary documentation for architecture design documentation. It should provide a high-level overview of the software system, including its components, modules, and interfaces. It should describe how the system will be deployed and integrated with other systems, and it should provide an overview of the technologies that will be used.

#### System Architecture Diagram

A simple diagram that shows the high-level structure of the system and how its components interact with each other. This can help the team understand the overall architecture of the system and how different parts of the system work together.

#### System Context Diagram

This diagram shows the relationships between the software system and its external entities, such as users, other systems, and devices. It can help to provide a high-level view of the system and its interactions with the outside world.

#### Deployment Diagram

This diagram shows how the software system will be deployed across hardware and network infrastructure. It can help to ensure that the system is deployed in a way that meets performance, scalability, and availability requirements.

#### Technical Requirements

This documentation outlines the technical requirements of the software system, including hardware, software, and network requirements. It should include information about the system's performance, scalability, and security.

#### Data Flow Diagram

This diagram shows the flow of data through the software system, including how it is input, processed, and output. It should describe the various data sources and destinations, as well as the transformations that occur.

### Detailed Design Documentation

This documentation provides a detailed specification of the software system, including its data structures, algorithms, and user interface. It should include a description of how the system will be implemented and tested.

#### Software Design Specification

This documentation provides a detailed description of the software system's design, including the software architecture, algorithms, and data structures used. It should describe the modules and components of the system, as well as their interactions and dependencies.

#### Sequence Diagram

This diagram shows the interaction between objects and classes in the software system, including the messages sent and received. It should describe the flow of control and data between the objects and classes. It can help to ensure that the system is designed to support the required functionality and to identify potential performance bottlenecks.

#### State Diagram

This diagram shows the behaviour of objects and classes in the software system, including the states and transitions between them. It should describe the events and actions that cause transitions between states.

#### Flowchart

This diagram shows the logical flow of operations in the software system, including the decision points and loops. It should describe the inputs, processes, and outputs of each operation.

#### User Interface Design

This documentation describes the design of the user interface, including the layout, navigation, and interaction of the system. It should provide a visual representation of the user interface and describe how it will be implemented.

#### Wireframes

Wireframes are visual representations of the user interface of the system. They can be used to help the team understand the layout and flow of the user interface, and to get feedback from stakeholders on the design.

#### Interface Design Document / Interface Control Document

This documentation describes the interfaces between the components of the software system. It should provide a detailed specification of the inputs, outputs, and behaviour of each interface. It should include a description of the data formats and protocols used, as well as any constraints or limitations.

#### Component Diagram

This diagram shows the components of the software system and their relationships to each other. It can help to ensure that the system is designed in a way that promotes modularity, maintainability, and extensibility.

#### Class Diagram

This diagram shows the structure of the software system at a detailed level, including the classes, objects, and their relationships. It should describe the attributes and methods of each class, as well as the inheritance and composition relationships between them.

#### Data Model

This document describes the data structures that are used by the software system. It should include a detailed description of the data entities and their relationships, as well as any constraints or rules that apply to the data.

#### Database Design

This documentation outlines the structure of the database(s) that will be used by the software. It may include ER diagrams, data flow diagrams, or SQL code.

### Test Plan Documentation

This documentation outlines the testing strategy for the software system, including the types of tests that will be performed, the test cases that will be used, and the expected results. It should also include a schedule for testing and any necessary resources or tools.

#### Test Plan Document

This documentation provides an overview of the testing strategies and procedures that will be used to verify the functionality and performance of the software system. It should describe the testing objectives, scope, and approach, as well as the resources and schedule required for testing.

#### Test Case Document

Test case documents provide detailed descriptions of the specific tests that will be performed to verify the functionality of the software system. They should include information about the test inputs, expected outputs, and test procedures.

#### Test Procedure Document

Test procedure documentation provide step-by-step instructions for performing the specific tests described in the test case document. They should describe the environment and setup required for testing, as well as any prerequisites or dependencies.

#### Test Report Document

Test report documents provide an overview of the results of the testing process, including any defects or issues that were identified. They should include a summary of the testing objectives, scope, and approach, as well as the resources and schedule required for testing.

### Code Documentation

Code documentation is an essential part of software development, as it helps developers understand how the code works and how to maintain it over time.

#### Code Comments

These are comments that are added to the code to explain how it works. They should be used to describe the purpose of the code, how it functions, and any important details that developers should be aware of.

#### README File

A README file is a document that provides an overview of the codebase, as well as any installation or usage instructions.

#### API Documentation

API documentation describes how the code interacts with other components and modules within the software system. It should describe the input parameters and return values for each API function, as well as any exceptions or error conditions that may arise.

#### User Manual

A user manual provides instructions on how to use the software system from the end-user's perspective. It should describe the different features and functionality of the system, as well as any usage instructions or examples.

#### Release Notes

Release notes provide information on the changes and updates that have been made to the software system in each release. It should include information on bug fixes, new features, and any other changes that have been made.

### Project Management

#### Project Management Plan / Project Plan

This plan outlines the overall approach to managing the software project, including the project scope, timelines, milestones, and deliverables. It also includes information on the project team structure, roles, and responsibilities.

A simple project plan can be created to outline the project scope, timelines, milestones, and deliverables. This plan can be used to track progress and ensure that the project stays on track.

#### Work Breakdown Structure (WBS)

This document breaks down the project into smaller, more manageable components or tasks, which can be tracked and managed more effectively.

#### Gantt Chart

This chart is used to visualise the project timeline and track the progress of each task over time. It helps to identify any potential delays or bottlenecks in the project schedule.

#### Risk Management Plan

This plan outlines the potential risks that could impact the project and the strategies that will be used to mitigate those risks. It includes information on risk identification, assessment, and response planning.

#### Change Management Plan

This plan outlines the process for managing changes to the project scope, schedule, or budget. It includes information on change request submission, review, and approval processes.

#### Status Reports

These reports provide regular updates on the project progress, including information on completed tasks, ongoing activities, and upcoming milestones. They help to ensure that all stakeholders are informed and up-to-date on the project status.

#### Issue Log

This log is used to track any issues or problems that arise during the project, including their impact, severity, and resolution status. It helps to ensure that issues are addressed in a timely and effective manner.

## Licensing Research

A MIT or GNU licence would be applicable if the app should be open sourced, and not wanting to keep IP private for a business case.

# Design Decisions

## Documentation Selection

Weighing up resources of the project, current need for detail in documentation given phase/maturity of project, and personal preference, some types of documentation will be selected early on and others may come later as the need rises (or never at all).

This document will be updated over time to reflect the current suite of documentation.

Documentation Status colour codes:

Completed

Selected to be done but pending completion

Documentation not currently selected has no highlight

### Requirements Documentation

Business Requirements Document (BRD) - While such a high level document is not really necessary for such a small project with a small team, starting this document off early will be worthwhile as detail can be built over time.

Functional Requirements Document (FRD) - Could likely get away with having functional requirements captured in more detailed design documentation, but starting this documentation early will also be worthwhile as it can be built up over time.

Non-Functional Requirements - Not at all necessary for most hobby projects, but a personal interest in performance, reliability, and security result in this being desirable.

Use Case Document - Given simplistic and well understood methods in which users will interact with the system, this doesn’t seem necessary.

User Stories - User Stories are not required as they serve a similar purpose as Use Cases, but to less detail, often used to capture requirements from prospective users. As the user requirements are well understood by the development team, these are deemed unnecessary.

Traceability Matrix - This will be extremely useful to enabling a methodical approach to requirements in design and testing, ensuring requirements are met individually.

The three selected documents listing requirements (Business Requirements Document, Functional Requirements Document, and Non-Functional Requirement Document) will be combined into a single for sake of scale and simplicity.

The Traceability Matrix will be implemented later on once usage is understood and requirements enumerated.

### Architecture Design Documentation

Architecture Design Document - Useful documentation, will be developed over time as decisions are made. More broad architecture is detailed here including integrations with other systems, and overview of other technologies/software used vs more internal architecture detailed in Software Design Specification.

System Architecture Diagram - Useful visual documentation, will be developed over time as decisions are made.

System Context Diagram - Deemed unnecessary, may be added later if required.

Deployment Diagram - Will be useful for ensuring Non-Functional requirements, but not worth completing initially.

Technical Requirements - Useful documentation, however most of this will be unknown until the software is mature.

Data Flow Diagram - Not required, may be added later if deemed necessary.

### Detailed Design Documentation

Software Design Specification - Useful document that can capture many facets of design, including: architecture, algorithms, data structures, components, interactions, and dependencies.

Sequence Diagram - Deemed unnecessary, may be added later if required.

State Diagram - Deemed unnecessary, may be added later if required.

Flowchart - Deemed unnecessary, may be added later if required.

User Interface Design - Will be created mainly to aid in the design of the web GUI tools as this will be a potentially busy and complex UI.

User Interface Design - Wireframes - Will be created as needed in the User Interface Design documentation.

Interface Design Document / Interface Control Document - Will be relatively easy to maintain and useful ensuring standardisation across endpoints. Content will be unknown until development is underway.

Component Diagram - Deemed unnecessary, may be added later if required.

Class Diagram - Not currently selected, but quite possibly added during development.

Data Model - Deemed unnecessary, may be added later if required.

Database Design - Not currently selected, may be completed during design depending if the schema is going to be controlled by the developer directly, or through an ORM tool controlling the schema automatically.

### Test Plan Documentation

Test Plan Document - Required, may come later. Will include a typical high level Test Plan Document at the project level, and per-microservice instances that will be a simple way to contain each microservice’s tests cases, procedure and reporting.

Test Case Document - This will be rolled into a per-microservice instance of a Test Plan Document.

Test Procedure Document - This will be rolled into a per-microservice instance of a Test Plan Document.

Test Report Document - This will be rolled into a per-microservice instance of a Test Plan Document.

### Code Documentation

Code Comments - Will be added as deemed necessary from inception.

README File -

API Documentation -

User Manual -

Release Notes -

### Project Management

Project Management Plan / Project Plan -

Work Breakdown Structure (WBS) -

Gantt Chart -

Risk Management Plan -

Change Management Plan -

Status Reports -

Issue Log -

## Licensing

No licence is being applied to the code repositories as no rights are being given, as this may become a profitable business. Licence may be added later on if open sourcing is desired.