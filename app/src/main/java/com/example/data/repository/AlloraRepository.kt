package com.example.data.repository

import com.example.core.SecurityUtils
import com.example.data.model.ApprovalRequest
import com.example.data.model.ApprovalStatus
import com.example.data.model.FlightOffer
import com.example.data.model.ItinerarySlot
import com.example.data.model.LedgerTransaction
import com.example.data.model.LodgingOffer
import com.example.data.model.RepoAuditItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class AlloraRepository {

    private val _auditItems = MutableStateFlow<List<RepoAuditItem>>(emptyList())
    val auditItems: StateFlow<List<RepoAuditItem>> = _auditItems.asStateFlow()

    private val _flightOffers = MutableStateFlow<List<FlightOffer>>(emptyList())
    val flightOffers: StateFlow<List<FlightOffer>> = _flightOffers.asStateFlow()

    private val _lodgingOffers = MutableStateFlow<List<LodgingOffer>>(emptyList())
    val lodgingOffers: StateFlow<List<LodgingOffer>> = _lodgingOffers.asStateFlow()

    private val _itinerarySlots = MutableStateFlow<List<ItinerarySlot>>(emptyList())
    val itinerarySlots: StateFlow<List<ItinerarySlot>> = _itinerarySlots.asStateFlow()

    private val _approvalRequests = MutableStateFlow<List<ApprovalRequest>>(emptyList())
    val approvalRequests: StateFlow<List<ApprovalRequest>> = _approvalRequests.asStateFlow()

    private val _ledgerTransactions = MutableStateFlow<List<LedgerTransaction>>(emptyList())
    val ledgerTransactions: StateFlow<List<LedgerTransaction>> = _ledgerTransactions.asStateFlow()

    init {
        loadAuditDataset()
        loadTravelFeeds()
        loadPendingSampleApprovals()
    }

    private fun loadAuditDataset() {
        val items = listOf(
            // PRIORITY TRAVEL REPOSITORIES
            RepoAuditItem(
                number = 9,
                name = "Weather Application",
                category = "Travel Priority",
                repoUrl = "https://github.com/adrianhajdin/project_weather_app",
                primaryStack = "React 17, Axios, CSS Modules",
                packageManager = "npm",
                dependenciesSummary = "axios, react, react-dom",
                database = "None (Client-side REST)",
                authMethod = "None",
                apiIntegrations = "OpenWeatherMap API (/data/2.5/weather)",
                isRealApi = true,
                apiRealismDetails = "Real live OpenWeatherMap API query via city name string.",
                bookingRealism = "Informational weather data only. No booking/transactions.",
                license = "MIT",
                securitySeverity = "CRITICAL",
                securityFindings = "API key embedded in client bundle via REACT_APP_API_KEY. Exposed in browser network inspect.",
                duplicateWith = "Overlaps with weather overlay in Repo #46.",
                recommendation = "REUSE LOGIC",
                preservedFiles = listOf("src/api/fetchWeather.js (normalize temperature, wind, condition icon)"),
                missingCredentials = listOf("OPENWEATHER_API_KEY (Server proxy required)")
            ),
            RepoAuditItem(
                number = 22,
                name = "Location-Based Service",
                category = "Travel Priority",
                repoUrl = "https://github.com/adrianhajdin/project_travel_companion",
                primaryStack = "React 17, Material-UI v4, Google Maps JS",
                packageManager = "npm",
                dependenciesSummary = "@material-ui/core, @material-ui/icons, axios, google-map-react",
                database = "None (Ephemeral client state)",
                authMethod = "None",
                apiIntegrations = "RapidAPI Travel Advisor API, Google Maps JavaScript API",
                isRealApi = true,
                apiRealismDetails = "Live POI discovery querying restaurants/hotels/attractions within coordinate boundaries.",
                bookingRealism = "No real transactional booking engine. Cards link to external TripAdvisor pages or display local state.",
                license = "MIT",
                securitySeverity = "CRITICAL",
                securityFindings = "RapidAPI key exposed on client headers. MUI v4 has ReDoS vulnerabilities and React 18 peer conflicts.",
                duplicateWith = "Near 85% duplicate with Repo #46.",
                recommendation = "MODIFY & ADAPT",
                preservedFiles = listOf("src/api/index.js (getPlacesData bounding box coordinate filter)"),
                missingCredentials = listOf("RAPIDAPI_KEY", "GOOGLE_MAPS_API_KEY (Restricted by package/referrer)")
            ),
            RepoAuditItem(
                number = 36,
                name = "Hotel Booking System",
                category = "Travel Priority",
                repoUrl = "https://github.com/machadop1407/hotel-management-full-stack",
                primaryStack = "MERN (MongoDB, Express.js, React 18, Node.js)",
                packageManager = "npm / yarn",
                dependenciesSummary = "express, mongoose, jsonwebtoken, bcryptjs, react, react-router-dom, axios",
                database = "MongoDB (Mongoose ODM)",
                authMethod = "JWT in HTTP-only cookies, bcrypt passwords",
                apiIntegrations = "Local Express endpoints; mock payment stub",
                isRealApi = false,
                apiRealismDetails = "All hotel rooms, pricing, and availability are static seed documents in MongoDB.",
                bookingRealism = "SIMULATED DEMO ONLY. Inserts a local booking document into MongoDB. Zero connection to real GDS/hotel suppliers.",
                license = "MIT",
                securitySeverity = "CRITICAL",
                securityFindings = "Race condition in room availability check (double-booking vulnerability). Hardcoded fallback JWT secret in code.",
                duplicateWith = "Overlaps with #48 (house rentals) and #47.",
                recommendation = "REPLACE CORE",
                preservedFiles = listOf("Date-range collision calculation logic; room occupancy validation algorithm"),
                missingCredentials = listOf("Amadeus Hospitality Search & Booking API credentials or Booking.com Affiliate API")
            ),
            RepoAuditItem(
                number = 45,
                name = "Car Rental System",
                category = "Travel Priority",
                repoUrl = "https://github.com/zeeshanhanif/car-rental-system",
                primaryStack = "React, Node.js / Express (or PHP/MySQL)",
                packageManager = "npm",
                dependenciesSummary = "express, mysql2 / mongodb, react, bootstrap",
                database = "MySQL / MongoDB vehicle tables",
                authMethod = "Session cookies / JWT",
                apiIntegrations = "Local vehicle inventory endpoints",
                isRealApi = false,
                apiRealismDetails = "Static car inventory (sedans, SUVs, luxury) from mock seed records.",
                bookingRealism = "DEMO FORM ONLY. Simulates rental checkout without provider telematics or enterprise fleet integration.",
                license = "MIT / Open Source",
                securitySeverity = "HIGH",
                securityFindings = "Unvalidated driver license uploads. Missing rate limiting on reservation routes.",
                duplicateWith = null,
                recommendation = "POSTPONE & REPLACE",
                preservedFiles = listOf("Daily rental pricing calculation model and insurance option toggles"),
                missingCredentials = listOf("Amadeus Car Rental API or Cartrawler Connect Agreement")
            ),
            RepoAuditItem(
                number = 46,
                name = "Travel Planner App",
                category = "Travel Priority",
                repoUrl = "https://github.com/adrianhajdin/project_travel_advisor",
                primaryStack = "React 17/18, Material-UI, Google Maps, RapidAPI",
                packageManager = "npm",
                dependenciesSummary = "@material-ui/core, axios, google-map-react",
                database = "None",
                authMethod = "None",
                apiIntegrations = "RapidAPI Travel Advisor API, OpenWeatherMap API, Google Maps",
                isRealApi = true,
                apiRealismDetails = "Live POI endpoints (restaurants, attractions, hotels) filtered by bounds (ne, sw).",
                bookingRealism = "Discovery only. Clicking cards links out or shows review ratings. Zero transactional capability.",
                license = "MIT",
                securitySeverity = "CRITICAL",
                securityFindings = "Exposed client secrets in bundle. Duplicate code with Repo #22.",
                duplicateWith = "Duplicate of Repo #22.",
                recommendation = "MODIFY & ADAPT",
                preservedFiles = listOf("src/components/PlaceDetails/PlaceDetails.jsx (card structure for POIs)"),
                missingCredentials = listOf("RAPIDAPI_KEY", "GOOGLE_MAPS_API_KEY")
            ),
            RepoAuditItem(
                number = 80,
                name = "Flight Booking System",
                category = "Travel Priority",
                repoUrl = "https://github.com/coding-dojo-sndip/flight-booking-system",
                primaryStack = "Python/Django or Java/Spring MVC or Node.js/Express",
                packageManager = "pip / npm / maven",
                dependenciesSummary = "Framework web core, SQLite / MySQL connectors",
                database = "SQLite / MySQL mock flights schema",
                authMethod = "Session auth",
                apiIntegrations = "Internal SQL queries against dummy flights table",
                isRealApi = false,
                apiRealismDetails = "100% SYNTHETIC MOCK DATA. Flight numbers, routes, and fares are static educational seeds.",
                bookingRealism = "SIMULATED DEMO ONLY. Generates dummy ticket strings. Zero airline GDS/NDC integration (no Amadeus, no Duffel, no Sabre).",
                license = "MIT / Educational",
                securitySeverity = "HIGH",
                securityFindings = "Insecure Direct Object Reference (IDOR) on ticket retrieval (/ticket/:id without owner check). Plaintext passenger info.",
                duplicateWith = null,
                recommendation = "REPLACE CORE",
                preservedFiles = listOf("Flight search input parameter contracts (Origin, Destination, CabinClass, PassengerManifest)"),
                missingCredentials = listOf("Amadeus Self-Service Flight Offers API credentials or Duffel.com NDC Access Token")
            ),

            // BROADER REPOSITORIES
            RepoAuditItem(
                number = 16,
                name = "Meal Planner App",
                category = "Dining & Health",
                repoUrl = "https://github.com/chingu-voyages/v25-geckos-team-65",
                primaryStack = "React, Node.js, Express, PostgreSQL",
                packageManager = "npm",
                dependenciesSummary = "react, express, pg, sequelize, axios",
                database = "PostgreSQL",
                authMethod = "JWT / BCrypt",
                apiIntegrations = "Spoonacular Recipe & Nutrition API",
                isRealApi = true,
                apiRealismDetails = "Live Spoonacular recipe search and macro calculations combined with local DB.",
                bookingRealism = "No commercial booking.",
                license = "MIT",
                securitySeverity = "MEDIUM",
                securityFindings = "Unsalted password hashing in older commits; outdated Sequelize ORM version.",
                duplicateWith = "Overlaps dining scheduling with Repo #17.",
                recommendation = "REUSE LOGIC",
                preservedFiles = listOf("Nutrition computation utilities and weekly meal matrix"),
                missingCredentials = listOf("SPOONACULAR_API_KEY")
            ),
            RepoAuditItem(
                number = 17,
                name = "Restaurant Menu Management",
                category = "Dining & Health",
                repoUrl = "https://github.com/ipenywis/restaurant-webapp",
                primaryStack = "React, Redux, Node.js",
                packageManager = "npm",
                dependenciesSummary = "react-redux, redux-thunk, express",
                database = "MongoDB / JSON",
                authMethod = "Admin token",
                apiIntegrations = "Internal menu CRUD endpoints",
                isRealApi = false,
                apiRealismDetails = "Local restaurant menu items and category filtering.",
                bookingRealism = "Internal cart simulation.",
                license = "MIT",
                securitySeverity = "MEDIUM",
                securityFindings = "CORS wildcard on all routes; lack of input sanitation on menu descriptions.",
                duplicateWith = "Overlaps with #16 and #37.",
                recommendation = "MODIFY & ADAPT",
                preservedFiles = listOf("Menu category grouping & dietary flag filter logic"),
                missingCredentials = emptyList()
            ),
            RepoAuditItem(
                number = 18,
                name = "Budget Planning App",
                category = "Finances & Ledger",
                repoUrl = "https://github.com/enaqx/awesome-react#expense-tracker",
                primaryStack = "React, Context API, CSS3",
                packageManager = "npm",
                dependenciesSummary = "react, uuid",
                database = "LocalStorage / Memory",
                authMethod = "None",
                apiIntegrations = "None (Client mathematical logic)",
                isRealApi = true,
                apiRealismDetails = "Real client-side mathematical ledger calculations (Income, Expense, Balance).",
                bookingRealism = "Local transaction ledger only.",
                license = "MIT",
                securitySeverity = "LOW",
                securityFindings = "No cryptographic integrity for financial amounts; easily modified in localStorage.",
                duplicateWith = "Connects to #41 (Banking ledger).",
                recommendation = "REUSE LOGIC",
                preservedFiles = listOf("Budget envelope math and multi-currency expense ledger algorithms"),
                missingCredentials = emptyList()
            ),
            RepoAuditItem(
                number = 23,
                name = "Kepler.gl Map Visualization",
                category = "Geospatial & Mobility",
                repoUrl = "https://github.com/uber/kepler.gl",
                primaryStack = "React, Redux, Deck.gl, WebGL, Mapbox GL",
                packageManager = "yarn / npm",
                dependenciesSummary = "@deck.gl/core, react-map-gl, d3-scale, kepler.gl",
                database = "In-memory GeoJSON / CSV layers",
                authMethod = "None",
                apiIntegrations = "Mapbox Vector Tiles API",
                isRealApi = true,
                apiRealismDetails = "Real high-performance GPU-accelerated geospatial data visualization.",
                bookingRealism = "Analytical visualizer; no booking capabilities.",
                license = "MIT",
                securitySeverity = "LOW",
                securityFindings = "Extremely heavy dependency tree (>100MB bundle); unfeasible for direct mobile embedding.",
                duplicateWith = null,
                recommendation = "ADAPT SUBSET",
                preservedFiles = listOf("Color ramp generator and GeoJSON coordinate arc interpolators"),
                missingCredentials = listOf("MAPBOX_ACCESS_TOKEN")
            ),
            RepoAuditItem(
                number = 37,
                name = "Restaurant Reservation System",
                category = "Dining & Health",
                repoUrl = "https://github.com/mikhae128/restaurant-reservation",
                primaryStack = "React, Express.js, MongoDB",
                packageManager = "npm",
                dependenciesSummary = "express, mongoose, react, react-datepicker",
                database = "MongoDB",
                authMethod = "JWT",
                apiIntegrations = "Internal table availability check",
                isRealApi = false,
                apiRealismDetails = "Table layouts and reservations saved in local MongoDB.",
                bookingRealism = "INTERNAL SIMULATION. Zero connection to OpenTable, SevenRooms, or Resy APIs.",
                license = "MIT",
                securitySeverity = "HIGH",
                securityFindings = "No lock on table reservations (simultaneous booking creates duplicates).",
                duplicateWith = "Overlaps with #16 and #17.",
                recommendation = "REPLACE CORE",
                preservedFiles = listOf("Party size & seating time interval algorithm"),
                missingCredentials = listOf("OpenTable API or SevenRooms Partner Integration")
            ),
            RepoAuditItem(
                number = 38,
                name = "Movie Ticket Booking System",
                category = "Entertainment",
                repoUrl = "https://github.com/isurumb/online-movie-ticket-booking-system",
                primaryStack = "React, Node.js, Express, Stripe API (Test)",
                packageManager = "npm",
                dependenciesSummary = "express, stripe, jsonwebtoken, react",
                database = "MongoDB / MySQL",
                authMethod = "JWT",
                apiIntegrations = "Stripe Checkout (Test Mode)",
                isRealApi = true,
                apiRealismDetails = "Uses real Stripe test tokens for payment intent creation.",
                bookingRealism = "Seating grid is internal. No connection to Fandango/Cinema chains.",
                license = "MIT",
                securitySeverity = "HIGH",
                securityFindings = "Client sends ticket price to backend instead of calculating from showtime ID.",
                duplicateWith = "Seat selection grid pattern reusable for flights (#80).",
                recommendation = "REUSE UI PATTERN",
                preservedFiles = listOf("Interactive seat map matrix with booked/selected/available state transitions"),
                missingCredentials = listOf("STRIPE_SECRET_KEY, STRIPE_PUBLISHABLE_KEY")
            ),
            RepoAuditItem(
                number = 39,
                name = "Calendso (Cal.com)",
                category = "Productivity & Calendar",
                repoUrl = "https://github.com/calendso/calendso",
                primaryStack = "Next.js, TypeScript, Prisma, PostgreSQL, Tailwind",
                packageManager = "yarn / pnpm",
                dependenciesSummary = "@prisma/client, next-auth, googleapis, @trpc/server",
                database = "PostgreSQL (Prisma ORM)",
                authMethod = "NextAuth (Google, Microsoft, OIDC, Credentials)",
                apiIntegrations = "Google Calendar API, Microsoft Graph, Apple Calendar, Zoom, Stripe",
                isRealApi = true,
                apiRealismDetails = "PRODUCTION GRADE. Live bi-directional synchronization with Google and Outlook calendars.",
                bookingRealism = "Real calendar slot reservation and event generation with meet links.",
                license = "AGPLv3 / Commercial",
                securitySeverity = "LOW",
                securityFindings = "Production-grade, enterprise hardened. AGPLv3 license requires isolated microservice boundary.",
                duplicateWith = null,
                recommendation = "REUSE AS ISOLATED SERVICE",
                preservedFiles = listOf("Calendar event synchronization pipeline and timezone conflict resolution routines"),
                missingCredentials = listOf("GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, MS_GRAPH_CLIENT_ID")
            ),
            RepoAuditItem(
                number = 40,
                name = "Grocery Delivery App",
                category = "Commerce",
                repoUrl = "https://github.com/shakyShane/grocery-app",
                primaryStack = "React Native / React, Redux, Express",
                packageManager = "npm",
                dependenciesSummary = "react-native, redux, express",
                database = "In-memory / SQLite",
                authMethod = "Phone OTP stub",
                apiIntegrations = "Mock product catalog",
                isRealApi = false,
                apiRealismDetails = "Static product catalog and mock delivery slot selection.",
                bookingRealism = "Simulated delivery order.",
                license = "MIT",
                securitySeverity = "MEDIUM",
                securityFindings = "Hardcoded phone verification bypass in debug builds.",
                duplicateWith = null,
                recommendation = "POSTPONE",
                preservedFiles = listOf("Shopping cart item calculation and delivery window selector"),
                missingCredentials = emptyList()
            ),
            RepoAuditItem(
                number = 41,
                name = "Banking Application",
                category = "Finances & Ledger",
                repoUrl = "https://github.com/TannerGabriel/Banking-Application",
                primaryStack = "Node.js, Express, MongoDB / EJS / React",
                packageManager = "npm",
                dependenciesSummary = "express, mongoose, passport, bcrypt",
                database = "MongoDB / PostgreSQL",
                authMethod = "Passport Local Sessions",
                apiIntegrations = "None (Demo ledger endpoints)",
                isRealApi = false,
                apiRealismDetails = "Simulated bank accounts, balances, and transfer receipts.",
                bookingRealism = "Demo balance transfer simulation.",
                license = "MIT",
                securitySeverity = "HIGH",
                securityFindings = "Missing idempotency keys on fund transfer; vulnerable to replay attacks.",
                duplicateWith = "Overlaps with #18.",
                recommendation = "REUSE SCHEMA",
                preservedFiles = listOf("Double-entry bookkeeping schema & transaction audit log structure"),
                missingCredentials = emptyList()
            ),
            RepoAuditItem(
                number = 44,
                name = "Hospital Management System",
                category = "Medical",
                repoUrl = "https://github.com/CodAffection/Hospital-Management-System",
                primaryStack = "Node.js / ASP.NET, SQL Server / MongoDB",
                packageManager = "npm",
                dependenciesSummary = "express, mssql / mongoose",
                database = "SQL Server / MongoDB",
                authMethod = "Role-based forms auth",
                apiIntegrations = "Internal patient CRUD",
                isRealApi = false,
                apiRealismDetails = "Internal clinic appointments and doctor schedules.",
                bookingRealism = "Local database record.",
                license = "MIT",
                securitySeverity = "HIGH",
                securityFindings = "Unencrypted medical notes in database (HIPAA non-compliant).",
                duplicateWith = "Overlaps scheduling with #39.",
                recommendation = "POSTPONE",
                preservedFiles = listOf("Doctor specialization filter hierarchy"),
                missingCredentials = emptyList()
            ),
            RepoAuditItem(
                number = 47,
                name = "Real Estate Listing Platform",
                category = "Lodging & Real Estate",
                repoUrl = "https://github.com/safak/youtube/tree/real-estate-app",
                primaryStack = "React, SCSS, Node.js, Express",
                packageManager = "npm",
                dependenciesSummary = "react, react-router-dom, axios",
                database = "MongoDB",
                authMethod = "JWT cookies",
                apiIntegrations = "Internal property search",
                isRealApi = false,
                apiRealismDetails = "Static rental properties in mock database.",
                bookingRealism = "Listing directory; no booking.",
                license = "MIT",
                securitySeverity = "MEDIUM",
                securityFindings = "Cleartext password stored in development config.",
                duplicateWith = "Near duplicate with #48.",
                recommendation = "POSTPONE",
                preservedFiles = listOf("Property feature tags & filter slider components"),
                missingCredentials = emptyList()
            ),
            RepoAuditItem(
                number = 48,
                name = "House Rental Platform",
                category = "Lodging & Real Estate",
                repoUrl = "https://github.com/machadop1407/real-estate-app",
                primaryStack = "React, Node.js, Express, MongoDB",
                packageManager = "npm",
                dependenciesSummary = "express, mongoose, jsonwebtoken, react",
                database = "MongoDB",
                authMethod = "JWT in HTTP-only cookies",
                apiIntegrations = "Local listing and reservation routes",
                isRealApi = false,
                apiRealismDetails = "Simulated vacation home listings with photo galleries.",
                bookingRealism = "Demo booking document created in MongoDB.",
                license = "MIT",
                securitySeverity = "HIGH",
                securityFindings = "Race condition on rental dates; missing price validation on server.",
                duplicateWith = "Duplicate of #47; overlaps lodging with #36.",
                recommendation = "MODIFY & ADAPT",
                preservedFiles = listOf("Vacation stay amenity checklist and photo gallery viewer"),
                missingCredentials = emptyList()
            ),
            RepoAuditItem(
                number = 97,
                name = "Surprise Recommendation System",
                category = "AI & Recommendations",
                repoUrl = "https://github.com/NicolasHug/Surprise",
                primaryStack = "Python 3, Cython, NumPy, SciPy",
                packageManager = "pip / conda",
                dependenciesSummary = "scikit-learn, numpy, scipy, cython",
                database = "Matrix arrays (MovieLens format)",
                authMethod = "None",
                apiIntegrations = "Local CSV datasets",
                isRealApi = true,
                apiRealismDetails = "Real SVD, SVD++, and KNN baseline collaborative filtering algorithms.",
                bookingRealism = "Offline ML algorithm library; no booking.",
                license = "BSD 3-Clause",
                securitySeverity = "LOW",
                securityFindings = "Python C-extension dependencies not portable to mobile clients without compilation overhead.",
                duplicateWith = null,
                recommendation = "REPLACE WITH LLM ORCHESTRATION",
                preservedFiles = listOf("Cosine similarity and user-preference scoring math"),
                missingCredentials = emptyList()
            ),
            RepoAuditItem(
                number = 98,
                name = "VROOM Route Optimizer",
                category = "Geospatial & Mobility",
                repoUrl = "https://github.com/VROOM-Project/vroom",
                primaryStack = "C++17, OpenStreetMap, OSRM / Valhalla",
                packageManager = "make / cmake / apt",
                dependenciesSummary = "libosrm, boost, asio",
                database = "In-memory matrix calculation",
                authMethod = "None",
                apiIntegrations = "OSRM (Open Source Routing Machine) or Valhalla routing engines",
                isRealApi = true,
                apiRealismDetails = "PRODUCTION HIGH-PERFORMANCE Vehicle Routing Problem (VRP) solver.",
                bookingRealism = "Routing engine; calculates optimal travel itineraries.",
                license = "BSD 2-Clause",
                securitySeverity = "LOW",
                securityFindings = "Industrial-grade C++ engine. Recommended as isolated microservice via Docker container.",
                duplicateWith = null,
                recommendation = "REUSE AS ISOLATED SERVICE",
                preservedFiles = listOf("VRP problem input specification (jobs, shipments, vehicles, time windows)"),
                missingCredentials = listOf("OSRM routing server or Mapbox Directions API key")
            )
        )
        _auditItems.value = items
    }

    private fun loadTravelFeeds() {
        val flights = listOf(
            FlightOffer(
                id = "fl-001",
                airline = "Singapore Airlines",
                flightNumber = "SQ 318",
                originIata = "SIN",
                destinationIata = "LHR",
                originCity = "Singapore",
                destinationCity = "London",
                departureTime = "12:45 PM",
                arrivalTime = "07:10 PM",
                duration = "13h 25m",
                priceUsd = 840.0,
                cabinClass = "Business",
                stops = 0,
                providerSource = "Sample Catalog • Amadeus Credentials Required"
            ),
            FlightOffer(
                id = "fl-002",
                airline = "All Nippon Airways (ANA)",
                flightNumber = "NH 008",
                originIata = "HND",
                destinationIata = "SFO",
                originCity = "Tokyo",
                destinationCity = "San Francisco",
                departureTime = "05:00 PM",
                arrivalTime = "10:20 AM",
                duration = "9h 20m",
                priceUsd = 720.0,
                cabinClass = "Economy",
                stops = 0,
                providerSource = "Sample Catalog • Duffel Token Required"
            ),
            FlightOffer(
                id = "fl-003",
                airline = "Air France",
                flightNumber = "AF 066",
                originIata = "CDG",
                destinationIata = "JFK",
                originCity = "Paris",
                destinationCity = "New York",
                departureTime = "10:15 AM",
                arrivalTime = "12:55 PM",
                duration = "8h 40m",
                priceUsd = 610.0,
                cabinClass = "Premium Economy",
                stops = 0,
                providerSource = "Sample Catalog • Amadeus Credentials Required"
            ),
            FlightOffer(
                id = "fl-004",
                airline = "Emirates",
                flightNumber = "EK 201",
                originIata = "DXB",
                destinationIata = "JFK",
                originCity = "Dubai",
                destinationCity = "New York",
                departureTime = "08:30 AM",
                arrivalTime = "02:15 PM",
                duration = "13h 45m",
                priceUsd = 980.0,
                cabinClass = "Business",
                stops = 0,
                providerSource = "Sample Catalog • Amadeus Credentials Required"
            )
        )
        _flightOffers.value = flights

        val lodgings = listOf(
            LodgingOffer(
                id = "ht-101",
                name = "Aman Tokyo Sanctuary",
                location = "Otemachi, Chiyoda City, Tokyo",
                rating = 4.9,
                reviewCount = 1420,
                pricePerNightUsd = 620.0,
                amenities = listOf("Spa & Onsen", "Fine Dining", "High-Speed WiFi", "Skyline View"),
                roomType = "Deluxe Imperial Suite",
                providerSource = "Sample Catalog • Partner Credentials Required"
            ),
            LodgingOffer(
                id = "ht-102",
                name = "Hôtel Plaza Athénée",
                location = "Avenue Montaigne, Paris",
                rating = 4.8,
                reviewCount = 2100,
                pricePerNightUsd = 780.0,
                amenities = listOf("Eiffel View", "Michelin Dining", "Dior Spa", "Concierge"),
                roomType = "Eiffel Signature Suite",
                providerSource = "Sample Catalog • Partner Credentials Required"
            ),
            LodgingOffer(
                id = "ht-103",
                name = "Marina Bay Sands Luxury Tower",
                location = "Bayfront Avenue, Singapore",
                rating = 4.7,
                reviewCount = 4850,
                pricePerNightUsd = 450.0,
                amenities = listOf("Infinity Pool", "Club Lounge", "Casino", "Bespoke Valet"),
                roomType = "Sands Premier King",
                providerSource = "Sample Catalog • Partner Credentials Required"
            )
        )
        _lodgingOffers.value = lodgings
    }

    private fun loadPendingSampleApprovals() {
        val approvals = listOf(
            ApprovalRequest(
                id = "appr-01",
                title = "Flight Hold Request (Singapore Airlines SQ 318)",
                category = "Flight Booking",
                merchantOrProvider = "Singapore Airlines (Sample Catalog)",
                amountUsd = 840.0,
                riskLevel = "Medium Risk",
                status = ApprovalStatus.PENDING,
                details = "SIN ➔ LHR Business Class. External airline GDS credentials required for live ticketing.",
                providerPolicy = "Staged hold only — not charged or ticketed without backend GDS confirmation.",
                expiresInMinutes = 48,
                isBookableWithProvider = false
            ),
            ApprovalRequest(
                id = "appr-02",
                title = "Hotel Stay Request (Aman Tokyo 2 Nights)",
                category = "Accommodation",
                merchantOrProvider = "Aman Resorts (Sample Catalog)",
                amountUsd = 1240.0,
                riskLevel = "Low Risk",
                status = ApprovalStatus.PENDING,
                details = "Deluxe Imperial Suite (2 Nights). External hospitality partner credentials required.",
                providerPolicy = "Staged hold only — partner reservation endpoint not connected.",
                expiresInMinutes = 120,
                isBookableWithProvider = false
            ),
            ApprovalRequest(
                id = "appr-03",
                title = "Dining Hold Request (Ginza Omakase)",
                category = "Dining Reservation",
                merchantOrProvider = "Sushi Sawada (Sample Catalog)",
                amountUsd = 260.0,
                riskLevel = "Low Risk",
                status = ApprovalStatus.PENDING,
                details = "Chef counter seating for 2 guests. 01:00 PM slot.",
                providerPolicy = "Staged hold only — restaurant reservation partner API required.",
                expiresInMinutes = 35,
                isBookableWithProvider = false
            )
        )
        _approvalRequests.value = approvals
    }

    fun approveRequest(
        requestId: String,
        userId: String = "local-session",
        referenceOverride: String? = null
    ): LedgerTransaction? {
        val current = _approvalRequests.value.toMutableList()
        val index = current.indexOfFirst { it.id == requestId }
        if (index == -1) return null

        val req = current[index]
        current[index] = req.copy(status = ApprovalStatus.APPROVED)
        _approvalRequests.value = current

        val nowMs = System.currentTimeMillis()
        val refCode = referenceOverride ?: "ALR-${UUID.randomUUID().toString().take(4).uppercase()}-${req.category.take(3).uppercase()}"
        val payload = SecurityUtils.buildBookingAuditPayload(
            userId = userId,
            referenceCode = refCode,
            bookingType = req.category,
            title = req.title,
            amountUsd = req.amountUsd,
            createdAtMs = nowMs
        )
        val digest = SecurityUtils.sha256Hex(payload)
        val formattedTime = DateTimeFormatter.ofPattern("MMM d, yyyy • HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(nowMs))

        val newTx = LedgerTransaction(
            id = "tx-" + UUID.randomUUID().toString().take(8),
            referenceCode = refCode,
            title = req.title,
            category = req.category,
            amountUsd = req.amountUsd,
            providerPnr = null,
            timestamp = "$formattedTime • User Approved",
            status = if (req.isBookableWithProvider) "USER_APPROVED" else "NOT_BOOKABLE",
            sha256Hash = digest
        )
        _ledgerTransactions.value = listOf(newTx) + _ledgerTransactions.value
        return newTx
    }

    fun markLedgerCancelled(referenceCode: String) {
        _ledgerTransactions.updateIfFound(referenceCode) { it.copy(status = "CANCELLED") }
    }

    private inline fun MutableStateFlow<List<LedgerTransaction>>.updateIfFound(
        referenceCode: String,
        transform: (LedgerTransaction) -> LedgerTransaction
    ) {
        value = value.map { if (it.referenceCode == referenceCode) transform(it) else it }
    }

    fun rejectRequest(requestId: String) {
        val current = _approvalRequests.value.toMutableList()
        val index = current.indexOfFirst { it.id == requestId }
        if (index != -1) {
            current[index] = current[index].copy(status = ApprovalStatus.REJECTED)
            _approvalRequests.value = current
        }
    }

    fun requestBookingHold(offer: FlightOffer) {
        val newApproval = ApprovalRequest(
            id = "appr-" + UUID.randomUUID().toString().take(6),
            title = "Flight Reservation (${offer.airline} ${offer.flightNumber})",
            category = "Flight Booking",
            merchantOrProvider = "${offer.airline} (via ${offer.providerSource})",
            amountUsd = offer.priceUsd,
            riskLevel = "Medium Risk",
            status = ApprovalStatus.PENDING,
            details = "${offer.originCity} (${offer.originIata}) ➔ ${offer.destinationCity} (${offer.destinationIata}). ${offer.cabinClass} Class. Non-stop.",
            providerPolicy = "Price held for 2 hours. Provider cancellation allowed within 24 hours.",
            expiresInMinutes = 60
        )
        _approvalRequests.value = listOf(newApproval) + _approvalRequests.value
    }

    fun requestLodgingHold(offer: LodgingOffer) {
        val newApproval = ApprovalRequest(
            id = "appr-" + UUID.randomUUID().toString().take(6),
            title = "Hotel Booking (${offer.name})",
            category = "Accommodation",
            merchantOrProvider = "${offer.name} (via ${offer.providerSource})",
            amountUsd = offer.pricePerNightUsd * 2,
            riskLevel = "Low Risk",
            status = ApprovalStatus.PENDING,
            details = "${offer.location} • ${offer.roomType} (2 Nights). Rating: ${offer.rating}★.",
            providerPolicy = "Free cancellation up to 48 hours before check-in. Instant confirmation.",
            expiresInMinutes = 120
        )
        _approvalRequests.value = listOf(newApproval) + _approvalRequests.value
    }

    fun requestCustomHold(
        title: String,
        category: String,
        merchantOrProvider: String,
        amountUsd: Double,
        details: String,
        providerPolicy: String,
        riskLevel: String = "Low Risk",
        expiresInMinutes: Int = 60
    ) {
        val newApproval = ApprovalRequest(
            id = "appr-" + UUID.randomUUID().toString().take(6),
            title = title,
            category = category,
            merchantOrProvider = merchantOrProvider,
            amountUsd = amountUsd,
            riskLevel = riskLevel,
            status = ApprovalStatus.PENDING,
            details = details,
            providerPolicy = providerPolicy,
            expiresInMinutes = expiresInMinutes
        )
        _approvalRequests.value = listOf(newApproval) + _approvalRequests.value
    }
}
