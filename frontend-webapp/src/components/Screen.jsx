import { motion } from 'framer-motion';

const MotionDiv = motion.div;

// Экран вне основных вкладок; при смене экрана плавно исчезает (нужен AnimatePresence выше по дереву).
export default function Screen({ children }) {
  return (
    <MotionDiv className="screen" exit={{ opacity: 0 }}>
      {children}
    </MotionDiv>
  );
}
