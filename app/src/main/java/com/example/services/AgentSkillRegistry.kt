package com.example.services

enum class AgentSkillId(
    val title: String,
    val category: String,
    val summary: String,
    val defaultAutonomyTier: PermissionLevel,
    val starterGoal: String,
    val isOptionalSkill: Boolean = false
) {
    WEB_RESEARCH(
        title = "Web Research",
        category = "Research & Discovery",
        summary = "Search multiple sources, extract verified facts, and compile citations.",
        defaultAutonomyTier = PermissionLevel.LIMITED_AUTONOMY,
        starterGoal = "Research the latest advancements in on-device AI and compile a cited comparison report."
    ),
    DOCUMENT_CREATION(
        title = "Document Creation",
        category = "Productivity",
        summary = "Draft structured briefs, reports, specs, and project notes in your workspace.",
        defaultAutonomyTier = PermissionLevel.LIMITED_AUTONOMY,
        starterGoal = "Create a structured project brief with objectives, milestones, and risks saved to Workspace Notes."
    ),
    FILE_ORGANIZATION(
        title = "File Organization",
        category = "Workspace",
        summary = "Categorize, index, inspect, and attach user-isolated workspace files cleanly.",
        defaultAutonomyTier = PermissionLevel.ALWAYS_ASK,
        starterGoal = "Inspect my workspace files and organize an indexed summary by file type and project."
    ),
    SUMMARIZATION(
        title = "Summarization",
        category = "Intelligence",
        summary = "Distill long webpages, documents, threads, and emails into actionable takeaways.",
        defaultAutonomyTier = PermissionLevel.LIMITED_AUTONOMY,
        starterGoal = "Summarize my recent workspace notes and active tasks into concise key takeaways."
    ),
    DATA_ANALYSIS(
        title = "Data Analysis",
        category = "Analytics",
        summary = "Compare options, pricing, budgets, and tradeoffs with transparent calculations.",
        defaultAutonomyTier = PermissionLevel.LIMITED_AUTONOMY,
        starterGoal = "Analyze my spending limits and recent options comparison to highlight cost-effective tradeoffs."
    ),
    CODING_ASSISTANCE(
        title = "Coding Assistance",
        category = "Engineering",
        summary = "Architecture review, debugging, code structuring, and technical documentation.",
        defaultAutonomyTier = PermissionLevel.LIMITED_AUTONOMY,
        starterGoal = "Review an architecture checklist for clean state management, error classification, and testing."
    ),
    PROJECT_MANAGEMENT(
        title = "Project Management",
        category = "Projects",
        summary = "Break goals into milestones, link workspace assets, and track execution status.",
        defaultAutonomyTier = PermissionLevel.LIMITED_AUTONOMY,
        starterGoal = "Create a structured project plan with tasks, dependencies, and verification checkpoints."
    ),
    TRAVEL_PLANNING(
        title = "Travel Planning",
        category = "Optional Skill",
        summary = "Optional skill: itineraries, flights, stays, weather, landmarks, and transit guidance.",
        defaultAutonomyTier = PermissionLevel.ALWAYS_ASK,
        starterGoal = "Check Tokyo weather, discover top landmarks, and build a structured itinerary skill plan.",
        isOptionalSkill = true
    ),
    CALENDAR_ASSISTANCE(
        title = "Calendar Assistance",
        category = "Scheduling",
        summary = "Prepare schedules, agenda blocks, and reminders via controlled Android intents.",
        defaultAutonomyTier = PermissionLevel.ALWAYS_ASK,
        starterGoal = "Prepare a focused daily schedule and draft calendar event blocks for my top priorities."
    ),
    EMAIL_DRAFTING(
        title = "Email Drafting",
        category = "Communication",
        summary = "Draft polished emails and review inbox summaries with mandatory send confirmation.",
        defaultAutonomyTier = PermissionLevel.MANUAL_APPROVAL_REQUIRED,
        starterGoal = "Prepare an email draft summarizing my latest project progress and action items."
    ),
    BROWSER_NAVIGATION(
        title = "Browser Navigation",
        category = "ALLORA Browser",
        summary = "Isolated multi-tab navigation, page reading, search extraction, and safe form prep.",
        defaultAutonomyTier = PermissionLevel.ALWAYS_ASK,
        starterGoal = "Open the isolated ALLORA Browser to search authoritative documentation and extract key points."
    )
}

object AgentSkillRegistry {
    fun allSkills(): List<AgentSkillId> = AgentSkillId.entries

    fun findById(id: String): AgentSkillId? =
        AgentSkillId.entries.firstOrNull { it.name.equals(id, ignoreCase = true) }

    fun describeForPrompt(): String = AgentSkillId.entries.joinToString("\n") { skill ->
        "- ${skill.title} (${skill.defaultAutonomyTier.tierLabel()}): ${skill.summary}"
    }
}
