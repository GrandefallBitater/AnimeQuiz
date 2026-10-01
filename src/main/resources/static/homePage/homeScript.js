function animateCounters() {
    const counters = document.querySelectorAll('.stat-number');
    counters.forEach(counter => {
        const target = +counter.getAttribute('data-target');
        const duration = 2000; // 2 секунды
        const increment = target / (duration / 16); // ~60fps
        let current = 0;

        const updateCounter = () => {
            current += increment;
            if (current < target) {
                counter.textContent = Math.ceil(current).toLocaleString();
                requestAnimationFrame(updateCounter);
            } else {
                counter.textContent = target.toLocaleString() + '+';
            }
        };

        updateCounter();
    });
}

function revealOnScroll() {
    const elements = document.querySelectorAll('.reveal-on-scroll');
    elements.forEach(el => {
        const windowHeight = window.innerHeight;
        const elementTop = el.getBoundingClientRect().top;
        const elementVisible = 100;

        if (elementTop < windowHeight - elementVisible) {
            el.classList.add('visible');
        }
    });
}

let countersAnimated = false;
const statsSection = document.querySelector('.stats-section');

const observer = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
        if (entry.isIntersecting && !countersAnimated) {
            animateCounters();
            countersAnimated = true;
        }
    });
}, { threshold: 0.3 });

if (statsSection) observer.observe(statsSection);

window.addEventListener('scroll', () => {
    revealOnScroll();
});

document.addEventListener('DOMContentLoaded', () => {
    revealOnScroll();
});