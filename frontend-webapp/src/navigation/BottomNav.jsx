import Icon from '../components/Icon';

export default function BottomNav({ tabs, activeTab, onSelect, navRef, tutorialRefs, badgeCount }) {
  return (
    <div className="nav-wrapper" ref={navRef}>
      <div className="bottom-nav">
        {tabs.map((tab, i) => (
          <button key={tab.id} ref={tab.tutorialRef ? tutorialRefs[tab.tutorialRef] : null}
            className={activeTab === i ? 'active' : ''}
            aria-label={tab.label} aria-current={activeTab === i ? 'page' : undefined}
            onClick={() => onSelect(i)}>
            <div className="nav-icon-bg">
              <Icon name={tab.icon} />
              {tab.badge && badgeCount > 0 && (
                <span className="nav-badge">{badgeCount}</span>
              )}
            </div>
          </button>
        ))}
      </div>
    </div>
  );
}
