import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.security.login.LoginManager
import com.adaptavist.hapi.jira.mail.Mail
import java.text.SimpleDateFormat

// ============================================================
// ✏️  YOUR SETTINGS — THIS IS THE ONLY SECTION YOU NEED TO EDIT
// ============================================================

// Who should receive the report?
// Add as many email addresses as you need.
def recipients = [
    'admin@yourcompany.com',
    'manager@yourcompany.com',
]

// How many days without a login counts as dormant?
// Example: 90 = anyone who hasn't logged in for 3 months will appear in the report.
def cutoffDays = 90

// Should users who have NEVER logged in appear in the report?
//   true  = yes, include them (recommended — they may still hold a licence)
//   false = no, skip them
def includeNeverLoggedIn = true

// Which Jira groups count as licenced?
// Leave this list EMPTY to scan ALL groups in your instance (recommended to start with).
// Add specific group names if you only want to report on certain licence groups.
// Example:
//   [] as Set<String>                          → scans everyone
//   ['jira-software-users'] as Set<String>     → only Jira Software users
def licensedGroups = [] as Set<String>

// ============================================================
// 🔧 SCRIPT ENGINE — DO NOT EDIT BELOW THIS LINE
// ============================================================

def userManager  = ComponentAccessor.userManager
def groupManager = ComponentAccessor.groupManager
def loginManager = ComponentAccessor.getComponent(LoginManager)
def sdf          = new SimpleDateFormat("yyyy-MM-dd HH:mm")
def reportTime   = sdf.format(new Date())

// Work out the exact cut-off point in time.
long cutoffMs = System.currentTimeMillis() - (cutoffDays * 24L * 60L * 60L * 1_000L)

// ── Pre-fetch static data once — before the loop ─────────────
// Fetching these inside the loop would mean thousands of identical
// database calls on large instances. We fetch them once and reuse.

// All group names in the instance — only fetched when licensedGroups is empty.
Collection<String> allGroupNames = licensedGroups.isEmpty()
    ? groupManager.getAllGroupNames()
    : licensedGroups

// Directory name cache — directories never change during a script run,
// so we look each one up once and store it here by its ID.
Map<Long, String> directoryCache = [:]

// Total user count — used only for progress logging.
int totalCount = userManager.getTotalUserCount()
int processed  = 0

// ── Scan every active, licenced, dormant user ─────────────────
List<Map<String, Object>> results = []

userManager.getAllApplicationUsers().each { user ->
    processed++

    // Log progress every 1000 users so you can confirm the script is
    // still running on large instances. Check Jira's application log.
    if (processed % 1000 == 0) {
        log.warn("Dormant user scan: ${processed} / ${totalCount} users processed...")
    }

    // Skip accounts that are already deactivated in Jira.
    if (!user.active) return

    // Skip users who are not in any licenced group.
    Collection<String> userGroups     = groupManager.getGroupNamesForUser(user)
    Collection<String> licencedGroups = userGroups.intersect(allGroupNames)
    if (licencedGroups.isEmpty()) return

    // Check when they last logged in.
    // lastLogin will be null if they have never logged in at all.
    def loginInfo  = loginManager.getLoginInfo(user.name)
    Long lastLogin = loginInfo?.lastLoginTime

    if (lastLogin == null && !includeNeverLoggedIn) return
    if (lastLogin != null && lastLogin >= cutoffMs) return

    // Look up the directory this user belongs to.
    // Each directory is fetched once and cached — not once per user.
    // "Internal" = you can deactivate it directly in Jira.
    // "LDAP / Active Directory" = deactivate it in your company directory instead.
    String directory = directoryCache.computeIfAbsent(user.directoryId) {
        userManager.getDirectory(it)?.name ?: 'Unknown'
    }

    results << ([
        username      : user.name,
        displayName   : user.displayName,
        email         : user.emailAddress ?: '',
        lastLogin     : lastLogin ? sdf.format(new Date(lastLogin)) : 'NEVER',
        lastLoginMs   : lastLogin ?: 0L,
        loginCount    : loginInfo?.loginCount ?: 0L,
        directory     : directory,
        licencedGroups: licencedGroups.sort().join('; '),
    ] as Map<String, Object>)
}

log.warn("Dormant user scan complete: ${results.size()} dormant users found from ${totalCount} total.")

// Sort: most dormant first.
results.sort { Map<String, Object> a, Map<String, Object> b ->
    (a.lastLoginMs as Long) <=> (b.lastLoginMs as Long)
}

// ── Build the HTML email body ─────────────────────────────────
def thStyle   = 'padding:10px 14px;text-align:left;background:#0052CC;color:#ffffff;font-size:13px;font-weight:600;white-space:nowrap;'
def tdStyle   = 'padding:9px 14px;font-size:13px;color:#172B4D;vertical-align:middle;border-bottom:1px solid #F4F5F7;'
def trOdd     = 'background:#FFFFFF;'
def trEven    = 'background:#F4F5F7;'
def tagStyle  = 'display:inline-block;padding:2px 8px;border-radius:3px;font-size:11px;font-weight:600;margin:1px 2px;'

def body = new StringBuilder()

body << """
<!DOCTYPE html>
<html>
<body style="margin:0;padding:0;background:#F4F5F7;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;">
<div style="max-width:1000px;margin:32px auto;background:#ffffff;border-radius:6px;overflow:hidden;box-shadow:0 1px 3px rgba(0,0,0,0.12);">

  <!-- Header -->
  <div style="background:#0052CC;padding:24px 32px;">
    <h1 style="margin:0;color:#ffffff;font-size:22px;font-weight:700;">Dormant User Report</h1>
    <p style="margin:8px 0 0;color:#B3D4FF;font-size:13px;">
      Generated: ${reportTime} &nbsp;·&nbsp; Cutoff: ${cutoffDays} days &nbsp;·&nbsp; Dormant users found: <strong style="color:#ffffff;">${results.size()}</strong>
    </p>
  </div>
"""

if (results.isEmpty()) {
    body << """
  <div style="padding:32px;">
    <p style="color:#6B778C;font-size:14px;margin:0;">No dormant users were found with the current settings.</p>
  </div>
"""
} else {
    // Summary banner
    body << """
  <!-- Summary -->
  <div style="padding:20px 32px;background:#DEEBFF;border-bottom:1px solid #B3D4FF;">
    <p style="margin:0;font-size:13px;color:#0052CC;">
      The following <strong>${results.size()} users</strong> have not logged in for more than <strong>${cutoffDays} days</strong>.
      Review this list and deactivate any accounts that are no longer needed to free up licences.
    </p>
  </div>

  <!-- Table -->
  <div style="padding:24px 32px;">
    <table style="width:100%;border-collapse:collapse;font-size:13px;">
      <thead>
        <tr>
          <th style="${thStyle}">Username</th>
          <th style="${thStyle}">Display Name</th>
          <th style="${thStyle}">Email</th>
          <th style="${thStyle}">Last Login</th>
          <th style="${thStyle}">Login Count</th>
          <th style="${thStyle}">Directory</th>
          <th style="${thStyle}">Licenced Groups</th>
        </tr>
      </thead>
      <tbody>
"""

    results.eachWithIndex { Map<String, Object> r, int i ->
        def rowStyle    = i % 2 == 0 ? trOdd : trEven
        def neverStyle  = r.lastLogin == 'NEVER' ? 'color:#DE350B;font-weight:700;' : ''
        def isExternal  = !(r.directory as String).toLowerCase().contains('internal')
        def badgeStyle  = tagStyle + (isExternal
            ? 'background:#FFEBE6;color:#DE350B;'
            : 'background:#E3FCEF;color:#006644;')

        body << """
        <tr style="${rowStyle}">
          <td style="${tdStyle}"><strong>${r.username}</strong></td>
          <td style="${tdStyle}">${r.displayName}</td>
          <td style="${tdStyle}"><a href="mailto:${r.email}" style="color:#0052CC;text-decoration:none;">${r.email}</a></td>
          <td style="${tdStyle}${neverStyle}">${r.lastLogin}</td>
          <td style="${tdStyle}text-align:center;">${r.loginCount}</td>
          <td style="${tdStyle}"><span style="${badgeStyle}">${r.directory}</span></td>
          <td style="${tdStyle}">${r.licencedGroups}</td>
        </tr>"""
    }

    body << """
      </tbody>
    </table>
  </div>
"""
}

// Footer
body << """
  <!-- Footer -->
  <div style="padding:16px 32px;background:#F4F5F7;border-top:1px solid #DFE1E6;">
    <p style="margin:0;font-size:11px;color:#6B778C;">
      This report was generated automatically by ScriptRunner for Jira Data Center.
      &nbsp;·&nbsp; <strong>Green badge</strong> = deactivate in Jira Admin.
      &nbsp;·&nbsp; <strong>Red badge</strong> = deactivate in your company directory (LDAP/AD).
    </p>
  </div>

</div>
</body>
</html>
"""

// ── Send the email ────────────────────────────────────────────
// Only send if there are dormant users — no point emailing an empty report.
if (results.isEmpty()) {
    log.warn("Dormant user report: no dormant users found, email not sent.")
    return
}

Mail.send {
    setTo(*recipients)
    setSubject("Dormant User Report — ${results.size()} users found (cutoff: ${cutoffDays} days)")
    setHtml()
    setBody(body.toString())
}

log.warn("Dormant user report emailed to: ${recipients.join(', ')}")
